package com.usedcar.order.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.usedcar.common.BizException;
import com.usedcar.customer.mapper.CustomerMapper;
import com.usedcar.order.domain.AfterSale;
import com.usedcar.order.domain.SaleOrder;
import com.usedcar.order.dto.AfterSaleCreateDTO;
import com.usedcar.order.dto.SaleOrderCreateDTO;
import com.usedcar.order.mapper.AfterSaleMapper;
import com.usedcar.order.mapper.SaleOrderMapper;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.domain.VehicleCost;
import com.usedcar.vehicle.dto.VehicleStatusChangeDTO;
import com.usedcar.vehicle.mapper.VehicleCostMapper;
import com.usedcar.vehicle.mapper.VehicleMapper;
import com.usedcar.vehicle.service.VehicleService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 成交订单与售后闭环。
 *
 * 设计要点：成交登记是独立接口，不是「点一下状态流转顺手插一条订单」。
 * 后者会造出没有买方、没有成交价的脏订单，而且 sale_order.uk_vehicle_id
 * 这个防重复成交约束永远触发不了。现在一条订单写完才把车推进到 SOLD。
 *
 * 售后成本会同时写一笔 vehicle_cost（类型 OTHER），这样定价测算里的成本合计
 * 才包含售后花出去的钱，不会出现「有售后单但毛利没变」。
 */
@Service
public class OrderService {

    private final SaleOrderMapper saleOrderMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final CustomerMapper customerMapper;
    private final VehicleMapper vehicleMapper;
    private final VehicleCostMapper vehicleCostMapper;
    private final VehicleService vehicleService;

    public OrderService(SaleOrderMapper saleOrderMapper, AfterSaleMapper afterSaleMapper,
                        CustomerMapper customerMapper, VehicleMapper vehicleMapper,
                        VehicleCostMapper vehicleCostMapper, VehicleService vehicleService) {
        this.saleOrderMapper = saleOrderMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.customerMapper = customerMapper;
        this.vehicleMapper = vehicleMapper;
        this.vehicleCostMapper = vehicleCostMapper;
        this.vehicleService = vehicleService;
    }

    // ==================== 查 ====================

    public List<SaleOrder> listOrders(Long vehicleId) {
        return saleOrderMapper.selectList(new LambdaQueryWrapper<SaleOrder>()
                .eq(SaleOrder::getVehicleId, vehicleId)
                .orderByDesc(SaleOrder::getId));
    }

    public List<AfterSale> listAfterSales(Long vehicleId) {
        return afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getVehicleId, vehicleId)
                .orderByDesc(AfterSale::getId));
    }

    // ==================== 写 ====================

    /**
     * 成交登记：写订单 + 把车推进到 SOLD，同一个事务。
     * 员工调用时会先被 ApprovalGate 拦成申请单，批准后由 ApprovalService 调这个方法。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createSaleOrder(Long vehicleId, SaleOrderCreateDTO dto) {
        Vehicle vehicle = requireVehicle(vehicleId);
        if (!"ON_SALE".equals(vehicle.getStatus())) {
            throw new BizException(400, "只有在售车辆可以登记成交");
        }
        if (customerMapper.selectById(dto.customerId()) == null) {
            throw new BizException(400, "买方客户不存在");
        }
        Long exists = saleOrderMapper.selectCount(
                new LambdaQueryWrapper<SaleOrder>().eq(SaleOrder::getVehicleId, vehicleId));
        if (exists != null && exists > 0) {
            throw new BizException(400, "该车已有成交订单");
        }

        SaleOrder order = new SaleOrder();
        order.setOrderNo(nextOrderNo());
        order.setVehicleId(vehicleId);
        order.setCustomerId(dto.customerId());
        order.setDealPrice(dto.dealPrice());
        order.setDealDate(dto.dealDate() == null ? LocalDate.now() : dto.dealDate());
        order.setPayType(StringUtils.hasText(dto.payType()) ? dto.payType() : "FULL");
        order.setDeposit(dto.deposit() == null ? java.math.BigDecimal.ZERO : dto.deposit());
        // 成交销售默认取车辆对接人，不给前端伪造归属的机会
        order.setSalesUserId(vehicle.getOwnerUserId());
        order.setStatus("SIGNED");
        order.setRemark(dto.remark());

        try {
            saleOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发下两笔登记都查到 0 条，靠 uk_vehicle_id 拦住第二个
            throw new BizException(400, "该车已有成交订单");
        }

        vehicleService.changeStatus(vehicleId,
                new VehicleStatusChangeDTO("SOLD", "成交登记 " + order.getOrderNo()));
        return order.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long createAfterSale(Long vehicleId, AfterSaleCreateDTO dto) {
        Vehicle vehicle = requireVehicle(vehicleId);
        if ("CLEARED".equals(vehicle.getStatus())) {
            throw new BizException(400, "已清库的车辆不允许再新增售后单");
        }

        AfterSale row = new AfterSale()
                .setVehicleId(vehicleId)
                .setOrderId(dto.orderId())
                .setIssueType(dto.issueType())
                .setDescription(dto.description())
                .setCost(dto.cost())
                .setStatus("OPEN")
                .setHandlerId(StpUtil.getLoginIdAsLong());
        afterSaleMapper.insert(row);

        // 售后成本计入该车成本合计，定价测算才会跟着变
        if (dto.cost() != null && dto.cost().compareTo(java.math.BigDecimal.ZERO) > 0) {
            VehicleCost cost = new VehicleCost();
            cost.setVehicleId(vehicleId);
            cost.setCostType("OTHER");
            cost.setAmount(dto.cost());
            cost.setOccurDate(LocalDate.now());
            cost.setRemark("售后成本：" + dto.description());
            vehicleCostMapper.insert(cost);
        }
        return row.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void closeAfterSale(Long afterSaleId) {
        AfterSale row = afterSaleMapper.selectById(afterSaleId);
        if (row == null) {
            throw new BizException(404, "售后单不存在");
        }
        if (!"OPEN".equals(row.getStatus())) {
            throw new BizException(400, "该售后单已结案");
        }
        afterSaleMapper.update(null, new LambdaUpdateWrapper<AfterSale>()
                .eq(AfterSale::getId, afterSaleId)
                .set(AfterSale::getStatus, "CLOSED")
                .set(AfterSale::getClosedAt, LocalDateTime.now()));
    }

    // ==================== 内部 ====================

    private Vehicle requireVehicle(Long vehicleId) {
        Vehicle v = vehicleMapper.selectById(vehicleId);
        if (v == null) {
            throw new BizException(404, "车辆不存在");
        }
        return v;
    }

    /** SO + yyyyMMdd + 三位流水，和库存编号 UC... 同一套规则。 */
    private String nextOrderNo() {
        String prefix = "SO" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String max = saleOrderMapper.maxOrderNo(prefix);
        int seq = 1;
        if (StringUtils.hasText(max) && max.length() == prefix.length() + 3) {
            try {
                seq = Integer.parseInt(max.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                // 老格式订单号不参与流水，从 001 重新开始
            }
        }
        return prefix + String.format("%03d", seq);
    }
}
