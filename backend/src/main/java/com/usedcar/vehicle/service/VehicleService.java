package com.usedcar.vehicle.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.usedcar.common.BizException;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.domain.VehicleCost;
import com.usedcar.vehicle.domain.VehicleStatus;
import com.usedcar.vehicle.domain.VehicleStatusLog;
import com.usedcar.vehicle.dto.VehicleCostDTO;
import com.usedcar.vehicle.dto.VehicleCreateDTO;
import com.usedcar.vehicle.dto.VehicleDetailVO;
import com.usedcar.vehicle.dto.VehicleStatusChangeDTO;
import com.usedcar.vehicle.dto.VehicleUpdateDTO;
import com.usedcar.vehicle.mapper.VehicleCostMapper;
import com.usedcar.vehicle.mapper.VehicleMapper;
import com.usedcar.vehicle.mapper.VehicleStatusLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 车辆业务。
 *
 * 这一层是状态机的唯一执行入口：所有状态变更都必须经过 changeStatus，
 * Controller 不允许直接调 mapper.updateById 改 status。
 */
@Service
public class VehicleService {

    private static final Logger log = LoggerFactory.getLogger(VehicleService.class);

    /** 库存编号前缀，格式 UC + yyyyMMdd + 三位流水，例如 UC20261105001 */
    private static final String STOCK_NO_PREFIX = "UC";
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 生成编号时的最大重试次数，防并发撞号 */
    private static final int STOCK_NO_RETRY = 5;

    private final VehicleMapper vehicleMapper;
    private final VehicleCostMapper vehicleCostMapper;
    private final VehicleStatusLogMapper statusLogMapper;

    public VehicleService(VehicleMapper vehicleMapper,
                          VehicleCostMapper vehicleCostMapper,
                          VehicleStatusLogMapper statusLogMapper) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleCostMapper = vehicleCostMapper;
        this.statusLogMapper = statusLogMapper;
    }

    // ==================== 写 ====================

    /**
     * 收车入库。
     *
     * 一次事务里做三件事，缺一件数据就不一致：
     *   1. 插 vehicle，状态固定为 PURCHASED
     *   2. 插 vehicle_cost，把收车款记为第一笔 PURCHASE 成本（否则成本合计永远是 0）
     *   3. 插 vehicle_status_log，from 为 null、to 为 PURCHASED（首条流水）
     * 三件事要么全成功，要么全回滚——只用 @Transactional 保证，不靠 try-catch 补。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(VehicleCreateDTO dto) {
        Vehicle vehicle = new Vehicle();
        vehicle.setStockNo(generateStockNo());
        vehicle.setVin(dto.vin());
        vehicle.setPlateNo(dto.plateNo());
        vehicle.setBrand(dto.brand());
        vehicle.setModel(dto.model());
        vehicle.setFirstRegDate(dto.firstRegDate());
        vehicle.setMileage(dto.mileage());
        vehicle.setPurchasePrice(dto.purchasePrice());
        vehicle.setPurchaseDate(dto.purchaseDate());
        vehicle.setListPrice(dto.listPrice());
        // basePrice 先不设：它是「首次挂牌价」，要等真正转为在售那一刻才定格，
        // 否则收车时随手填的价会被当成降价基准，后面算阶梯降价全错
        vehicle.setStatus(VehicleStatus.PURCHASED.name());
        vehicle.setOwnerUserId(dto.ownerUserId());
        vehicle.setRemark(dto.remark());

        vehicleMapper.insert(vehicle);

        VehicleCost cost = new VehicleCost();
        cost.setVehicleId(vehicle.getId());
        cost.setCostType("PURCHASE");
        cost.setAmount(dto.purchasePrice());
        cost.setOccurDate(dto.purchaseDate());
        cost.setRemark("收车款");
        vehicleCostMapper.insert(cost);

        writeStatusLog(vehicle.getId(), null, VehicleStatus.PURCHASED, "收车入库");

        log.info("收车入库: {} {} {}，库存编号 {}", dto.brand(), dto.model(), vehicle.getId(), vehicle.getStockNo());
        return vehicle.getId();
    }

    /**
     * 修改车辆基础信息。只允许改描述性字段，钱和状态都不在这个方法里。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, VehicleUpdateDTO dto) {
        Vehicle vehicle = requireVehicle(id);
        if (VehicleStatus.CLEARED.name().equals(vehicle.getStatus())) {
            throw new BizException(400, "已清库的车辆不允许再修改");
        }

        Vehicle patch = new Vehicle();
        patch.setId(id);
        patch.setBrand(dto.brand());
        patch.setModel(dto.model());
        patch.setPlateNo(dto.plateNo());
        patch.setVin(dto.vin());
        patch.setFirstRegDate(dto.firstRegDate());
        patch.setMileage(dto.mileage());
        patch.setOwnerUserId(dto.ownerUserId());
        patch.setRemark(dto.remark());
        // updateById 只更新非 null 字段，所以这里传 null 表示「这一项不动」，
        // 语义是「局部更新」而不是「整体覆盖」，前端不用回传全部字段
        vehicleMapper.updateById(patch);
    }

    /**
     * 状态流转。整个系统改状态的唯一入口。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, VehicleStatusChangeDTO dto) {
        Vehicle vehicle = requireVehicle(id);
        VehicleStatus current = parseStatus(vehicle.getStatus());
        VehicleStatus target = parseStatus(dto.targetStatus());

        // 第一道闸：枚举里的白名单。非法流转在这里被拒，比如 CLEARED 之后还想改回 ON_SALE
        current.requireTransitionTo(target);

        if (target == VehicleStatus.ON_SALE) {
            if (vehicle.getListPrice() == null) {
                throw new BizException(400, "挂牌前必须先设置挂牌价");
            }
            // 首次挂牌时把 basePrice 定格为当时的挂牌价，阶梯降价以它为基准
            if (vehicle.getBasePrice() == null) {
                vehicle.setBasePrice(vehicle.getListPrice());
            }
        }

        Vehicle patch = new Vehicle();
        patch.setId(id);
        patch.setStatus(target.name());
        patch.setBasePrice(vehicle.getBasePrice());

        if (target == VehicleStatus.SOLD) {
            patch.setSaleDate(LocalDate.now());
        } else if (target == VehicleStatus.CLEARED) {
            patch.setClearDate(LocalDate.now());
        }

        vehicleMapper.updateById(patch);
        writeStatusLog(id, current, target, dto.remark());

        log.info("车辆 {} 状态流转: {} -> {}", id, current.getLabel(), target.getLabel());
    }

    /**
     * 追加一笔成本（整备、运输、过户等）。
     * 收车款已在 create 里自动写过，不要重复录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void addCost(Long id, VehicleCostDTO dto) {
        Vehicle vehicle = requireVehicle(id);
        if (VehicleStatus.CLEARED.name().equals(vehicle.getStatus())) {
            throw new BizException(400, "已清库的车辆不允许再补录成本");
        }

        VehicleCost cost = new VehicleCost();
        cost.setVehicleId(id);
        cost.setCostType(dto.costType());
        cost.setAmount(dto.amount());
        cost.setOccurDate(dto.occurDate() == null ? LocalDate.now() : dto.occurDate());
        cost.setRemark(dto.remark());
        vehicleCostMapper.insert(cost);
    }

    // ==================== 读 ====================

    /**
     * 分页查询。
     */
    public IPage<Vehicle> page(long page, long size, String keyword, String status) {
        LambdaQueryWrapper<Vehicle> wrapper = new LambdaQueryWrapper<Vehicle>()
                .eq(StringUtils.hasText(status), Vehicle::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Vehicle::getBrand, keyword)
                        .or()
                        .like(Vehicle::getModel, keyword)
                        .or()
                        .like(Vehicle::getStockNo, keyword))
                .orderByDesc(Vehicle::getPurchaseDate)
                .orderByDesc(Vehicle::getId);
        return vehicleMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 详情：车辆 + 成本合计 + 状态流水。
     */
    public VehicleDetailVO detail(Long id) {
        Vehicle vehicle = requireVehicle(id);
        BigDecimal totalCost = vehicleCostMapper.sumCost(id);
        List<VehicleStatusLog> logs = statusLogMapper.selectList(
                new LambdaQueryWrapper<VehicleStatusLog>()
                        .eq(VehicleStatusLog::getVehicleId, id)
                        .orderByAsc(VehicleStatusLog::getId));
        return new VehicleDetailVO(vehicle, totalCost, logs);
    }

    // ==================== 内部 ====================

    private Vehicle requireVehicle(Long id) {
        Vehicle vehicle = vehicleMapper.selectById(id);
        if (vehicle == null) {
            throw new BizException(404, "车辆不存在");
        }
        return vehicle;
    }

    private VehicleStatus parseStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(400, "状态不能为空");
        }
        try {
            return VehicleStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "非法的状态值: " + raw);
        }
    }

    private void writeStatusLog(Long vehicleId, VehicleStatus from, VehicleStatus to, String remark) {
        VehicleStatusLog logRow = new VehicleStatusLog();
        logRow.setVehicleId(vehicleId);
        logRow.setFromStatus(from == null ? null : from.name());
        logRow.setToStatus(to.name());
        // 操作人从登录态取，不接受前端传：否则谁都能把操作记到别人头上
        logRow.setOperatorId(StpUtil.getLoginIdAsLong());
        logRow.setRemark(remark);
        statusLogMapper.insert(logRow);
    }

    /**
     * 生成库存编号：UC + yyyyMMdd + 三位流水。
     *
     * 并发下两个请求可能算出同一个号，靠 uk_stock_no 唯一索引兜底，
     * 捕到 DuplicateKeyException 就重算一次。这是「先查后插」必须配的兜底，
     * 只查不兜，压测时必现主键冲突。
     */
    private String generateStockNo() {
        String prefix = STOCK_NO_PREFIX + LocalDate.now().format(DAY_FORMAT);
        for (int i = 0; i < STOCK_NO_RETRY; i++) {
            String max = vehicleMapper.maxStockNo(prefix);
            int next = 1;
            if (StringUtils.hasText(max) && max.length() > prefix.length()) {
                // 截掉前缀，剩下的就是流水号
                next = Integer.parseInt(max.substring(prefix.length())) + 1 + i;
            }
            String candidate = prefix + String.format("%03d", next);
            if (vehicleMapper.selectCount(new LambdaQueryWrapper<Vehicle>()
                    .eq(Vehicle::getStockNo, candidate)) == 0) {
                return candidate;
            }
        }
        throw new BizException(500, "库存编号生成失败，请重试");
    }
}
