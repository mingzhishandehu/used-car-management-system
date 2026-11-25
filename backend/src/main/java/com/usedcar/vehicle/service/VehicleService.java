package com.usedcar.vehicle.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.usedcar.common.BizException;
import com.usedcar.order.domain.AfterSale;
import com.usedcar.order.mapper.AfterSaleMapper;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.domain.VehicleCost;
import com.usedcar.vehicle.domain.PriceChangeLog;
import com.usedcar.vehicle.domain.VehicleStatus;
import com.usedcar.vehicle.domain.VehicleStatusLog;
import com.usedcar.vehicle.dto.VehicleCostDTO;
import com.usedcar.vehicle.dto.VehicleCreateDTO;
import com.usedcar.vehicle.dto.VehicleDetailVO;
import com.usedcar.vehicle.dto.VehicleStatusChangeDTO;
import com.usedcar.vehicle.dto.VehicleUpdateDTO;
import com.usedcar.vehicle.dto.VehiclePriceChangeDTO;
import com.usedcar.vehicle.mapper.VehicleCostMapper;
import com.usedcar.vehicle.mapper.VehicleMapper;
import com.usedcar.vehicle.mapper.VehicleStatusLogMapper;
import com.usedcar.vehicle.mapper.PriceChangeLogMapper;
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
    private final PriceChangeLogMapper priceChangeLogMapper;
    private final AfterSaleMapper afterSaleMapper;

    public VehicleService(VehicleMapper vehicleMapper,
                          VehicleCostMapper vehicleCostMapper,
                          VehicleStatusLogMapper statusLogMapper,
                          PriceChangeLogMapper priceChangeLogMapper,
                          AfterSaleMapper afterSaleMapper) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleCostMapper = vehicleCostMapper;
        this.statusLogMapper = statusLogMapper;
        this.priceChangeLogMapper = priceChangeLogMapper;
        this.afterSaleMapper = afterSaleMapper;
    }

    // ==================== 写 ====================

    /**
     * 收车入库。
     *
     * 一次事务里做四件事，缺一件数据就不一致：
     *   1. 插 vehicle，状态固定为 PURCHASED，同时把 created_by 写成当前登录人、
     *      owner_user_id 写成指定的对接人（未指定则取当前登录人）
     *   2. 插 vehicle_cost，把收车款记为第一笔 PURCHASE 成本（否则成本合计永远是 0）
     *   3. 插 vehicle_status_log，from 为 null、to 为 PURCHASED（首条流水）
     *   4. 上面三张表的写入共用同一个事务边界
     * 四件事要么全成功，要么全回滚——只用 @Transactional 保证，不靠 try-catch 补。
     *
     * 单参重载的归属从登录态取，必须在登录上下文里调用；
     * 三参重载由审批执行路径传入申请人，审批人本身也必须登录，前提同样成立。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(VehicleCreateDTO dto) {
        return create(dto, StpUtil.getLoginIdAsLong(), null);
    }

    /**
     * 内部重载：允许服务端编排层显式指定「业务归属人」和「审批人」。
     *
     * 为什么必须有：老板点「通过」时，登录态是老板，
     * 如果还用 StpUtil.getLoginIdAsLong() 写 created_by，
     * 所有经审批录入的车都会变成「老板增加的」，员工归属记录就废了。
     * 审批执行路径传入申请人的 id；ADMIN 直接录入走上面那个单参重载，approvedBy 为 null。
     *
     * 这个重载只给 ApprovalService 这类服务端编排层调用，Controller 不得直接调，
     * 否则外部又能伪造 created_by。
     *
     * 注意 status_log.operator_id 仍取当前登录态：它表达的是「谁执行了这次写入」，
     * 审批场景下确实是老板；created_by 表达的是「业务上谁增加的这辆车」，两者语义不同。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(VehicleCreateDTO dto, Long createdBy, Long approvedBy) {
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

        // createdBy 由调用方决定：HTTP 直录取登录态，审批通过取申请人。
        // 它不在 VehicleCreateDTO 里，前端传什么都不影响。
        vehicle.setCreatedBy(createdBy);
        vehicle.setApprovedBy(approvedBy);
        // 对接人允许前端指定（收车表单可改选别的员工）；没指定就默认等于录入人。
        // 老板用自己的 admin 账号收车、员工用 AI 收车经审批通过，两种场景都成立。
        vehicle.setOwnerUserId(dto.ownerUserId() != null ? dto.ownerUserId() : createdBy);
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

        // 第一道闸：枚举里的白名单。
        current.requireTransitionTo(target);

        // 第二道闸：V1 的 DDL 注释承诺「清库前必须售后闭环」，
        // 这条规则以前只写在注释里没有实现，订单模块接线后在这里真正校验。
        // 撤回清库不检查（current == CLEARED），否则撤回来又要重新结案，逻辑会死锁。
        if (target == VehicleStatus.CLEARED && current != VehicleStatus.CLEARED) {
            Long open = afterSaleMapper.selectCount(new LambdaQueryWrapper<AfterSale>()
                    .eq(AfterSale::getVehicleId, id)
                    .eq(AfterSale::getStatus, "OPEN"));
            if (open != null && open > 0) {
                throw new BizException(400, "还有 " + open + " 条未结案的售后单，不允许清库");
            }
        }

        // 清库撤回只能恢复最近一次流转的来源状态，不能借撤回入口任意修改车辆状态。
        if (current == VehicleStatus.CLEARED) {
            VehicleStatusLog latest = statusLogMapper.selectLatestByVehicleId(id);
            if (latest == null || !VehicleStatus.CLEARED.name().equals(latest.getToStatus())
                    || !target.name().equals(latest.getFromStatus())) {
                throw new BizException(400, "只能撤回最近一次清库，并恢复清库前的状态");
            }
        }

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

        if (target == VehicleStatus.SOLD && vehicle.getSaleDate() == null) {
            patch.setSaleDate(LocalDate.now());
        } else if (target == VehicleStatus.CLEARED) {
            patch.setClearDate(LocalDate.now());
        } else if (current == VehicleStatus.SOLD && target == VehicleStatus.ON_SALE) {
            // 撤销成交后回到在售，成交日期不再代表当前业务事实
            patch.setSaleDate(null);
        } else if (current == VehicleStatus.CLEARED) {
            // 撤销清库后恢复清库前状态，并清空清库日期
            patch.setClearDate(null);
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

    /** 调整挂牌价：车辆主表保留最新值，price_change_log 保留完整流水。 */
    @Transactional(rollbackFor = Exception.class)
    public void changePrice(Long id, VehiclePriceChangeDTO dto) {
        Vehicle vehicle = requireVehicle(id);
        if (VehicleStatus.CLEARED.name().equals(vehicle.getStatus())
                || VehicleStatus.SOLD.name().equals(vehicle.getStatus())
                || VehicleStatus.AFTER_SALE.name().equals(vehicle.getStatus())) {
            throw new BizException(400, "当前车辆状态不允许调整挂牌价");
        }

        BigDecimal oldPrice = vehicle.getListPrice();
        if (oldPrice != null && oldPrice.compareTo(dto.newPrice()) == 0) {
            throw new BizException(400, "新挂牌价不能与当前价格相同");
        }

        Vehicle patch = new Vehicle();
        patch.setId(id);
        patch.setListPrice(dto.newPrice());
        vehicleMapper.updateById(patch);

        PriceChangeLog logRow = new PriceChangeLog();
        logRow.setVehicleId(id);
        logRow.setOldPrice(oldPrice);
        logRow.setNewPrice(dto.newPrice());
        logRow.setReason(StringUtils.hasText(dto.reason()) ? dto.reason().trim() : "MANUAL");
        logRow.setOperatorId(StpUtil.getLoginIdAsLong());
        priceChangeLogMapper.insert(logRow);
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

    /**
     * 成本明细列表。
     * 合计给仪表盘和定价用，明细给「这一千二花在哪了」用，两者不能互相替代。
     */
    public List<VehicleCost> costs(Long id) {
        requireVehicle(id);
        return vehicleCostMapper.selectList(new LambdaQueryWrapper<VehicleCost>()
                .eq(VehicleCost::getVehicleId, id)
                .orderByDesc(VehicleCost::getOccurDate)
                .orderByDesc(VehicleCost::getId));
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
