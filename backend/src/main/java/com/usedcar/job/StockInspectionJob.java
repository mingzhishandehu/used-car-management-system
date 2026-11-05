package com.usedcar.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.usedcar.alert.AlertService;
import com.usedcar.pricing.PricingRuleService;
import com.usedcar.pricing.PricingService;
import com.usedcar.pricing.dto.VehiclePricingVO;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.mapper.VehicleCertificateMapper;
import com.usedcar.vehicle.mapper.VehicleCostMapper;
import com.usedcar.vehicle.mapper.VehicleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日库存巡检。
 *
 * 产出三个类型的预警：
 *   SLOW_MOVING  持有天数超过阈值（滞销）
 *   LOW_MARGIN   当前挂牌价已低于保本底价
 *   CERT_EXPIRE  证件在 N 天内到期
 *
 * 为什么用定时任务而不是在查询接口里顺手算：
 *   预警是「待办」，要有已读/忽略的状态，实时算的话这个状态没地方放，
 *   销售今天点了忽略，明天刷新又冒出来，很快就没人看了。
 */
@Component
public class StockInspectionJob {

    private static final Logger log = LoggerFactory.getLogger(StockInspectionJob.class);

    private final VehicleMapper vehicleMapper;
    private final VehicleCostMapper vehicleCostMapper;
    private final VehicleCertificateMapper certificateMapper;
    private final PricingService pricingService;
    private final PricingRuleService pricingRuleService;
    private final AlertService alertService;

    public StockInspectionJob(VehicleMapper vehicleMapper,
                              VehicleCostMapper vehicleCostMapper,
                              VehicleCertificateMapper certificateMapper,
                              PricingService pricingService,
                              PricingRuleService pricingRuleService,
                              AlertService alertService) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleCostMapper = vehicleCostMapper;
        this.certificateMapper = certificateMapper;
        this.pricingService = pricingService;
        this.pricingRuleService = pricingRuleService;
        this.alertService = alertService;
    }

    /**
     * 每天凌晨 2 点跑。cron 六个字段：秒 分 时 日 月 周。
     * "0 0 2 * * ?" = 每天 02:00:00 触发，周字段用 ? 是因为「日」和「周」互斥，必须有一个是 ?。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledInspect() {
        log.info("每日库存巡检开始");
        int created = inspect();
        log.info("每日库存巡检结束，新增预警 {} 条", created);
    }

    /**
     * 手动触发一次巡检。返回新增预警条数。
     * 有这个方法，演示和测试就不用等到凌晨两点，也不用改系统时间。
     */
    public int inspect() {
        int created = 0;
        created += inspectSlowMovingAndMargin();
        created += inspectCertificateExpiry();
        return created;
    }

    /**
     * 滞销与毛利巡检。
     *
     * 只巡检「已收车」「在售」两种状态：已成交、售后、已清库的车不占库存，算滞销没有意义。
     */
    private int inspectSlowMovingAndMargin() {
        List<Vehicle> vehicles = vehicleMapper.selectList(new LambdaQueryWrapper<Vehicle>()
                .in(Vehicle::getStatus, "PURCHASED", "ON_SALE"));
        if (vehicles.isEmpty()) {
            return 0;
        }

        // 一次查出所有车的成本，避免每台车查一次（N+1）
        List<Long> ids = new ArrayList<>(vehicles.size());
        for (Vehicle v : vehicles) {
            ids.add(v.getId());
        }
        Map<Long, BigDecimal> costMap = new HashMap<>(ids.size());
        for (Map<String, Object> row : vehicleCostMapper.sumCostByVehicleIds(ids)) {
            Object vid = row.get("vehicleId");
            Object total = row.get("total");
            if (vid != null) {
                costMap.put(((Number) vid).longValue(),
                        total == null ? BigDecimal.ZERO : new BigDecimal(total.toString()));
            }
        }

        int created = 0;
        for (Vehicle vehicle : vehicles) {
            VehiclePricingVO pricing = pricingService.calculate(vehicle, costMap.get(vehicle.getId()));

            // 滞销：只有 WARN 及以上才建待办，ATTENTION 只是关注，不值得占用销售的处理队列
            if ("WARN".equals(pricing.slowLevel()) || "DANGER".equals(pricing.slowLevel())) {
                String content = String.format("持有 %d 天，成本 %.2f，保本底价 %.2f，当前挂牌 %s，建议降至 %s",
                        pricing.holdingDays(),
                        pricing.totalCost(),
                        pricing.breakevenPrice(),
                        pricing.listPrice() == null ? "未挂牌" : pricing.listPrice().toPlainString(),
                        pricing.suggestedPrice() == null ? "-" : pricing.suggestedPrice().toPlainString());
                if (alertService.openIfAbsent(vehicle.getId(), "SLOW_MOVING",
                        "DANGER".equals(pricing.slowLevel()) ? "DANGER" : "WARN", content)) {
                    created++;
                }
            }

            // 毛利过低：挂牌价已经跌破保本线，卖一台亏一台，必须单独提醒
            if (pricing.belowBreakeven()) {
                String content = String.format("挂牌价 %.2f 低于保本底价 %.2f，卖出即亏损",
                        pricing.listPrice(), pricing.breakevenPrice());
                if (alertService.openIfAbsent(vehicle.getId(), "LOW_MARGIN", "DANGER", content)) {
                    created++;
                }
            }
        }
        return created;
    }

    /**
     * 证件到期巡检。
     *
     * 截止日 = 今天 + cert.warn.days（默认 30 天）。已经过期的证件同样会被查出来（expire_date <= deadline），
     * 这是有意的：过期比将要过期更紧急，不能因为「已经过了」就从列表里消失。
     */
    private int inspectCertificateExpiry() {
        int warnDays = pricingRuleService.rules().certWarnDays();
        LocalDate deadline = LocalDate.now().plusDays(warnDays);

        int created = 0;
        for (Map<String, Object> row : certificateMapper.selectExpiring(deadline)) {
            Long vehicleId = ((Number) row.get("vehicleId")).longValue();
            String certType = String.valueOf(row.get("certType"));
            String expireDate = String.valueOf(row.get("expireDate"));

            long daysLeft = LocalDate.parse(expireDate).toEpochDay() - LocalDate.now().toEpochDay();
            String level = daysLeft < 0 ? "DANGER" : (daysLeft <= 7 ? "DANGER" : "WARN");
            String content = daysLeft < 0
                    ? String.format("%s 已于 %s 过期 %d 天", certTypeLabel(certType), expireDate, -daysLeft)
                    : String.format("%s 将于 %s 到期，剩余 %d 天", certTypeLabel(certType), expireDate, daysLeft);

            if (alertService.openIfAbsent(vehicleId, "CERT_EXPIRE", level, content)) {
                created++;
            }
        }
        return created;
    }

    private String certTypeLabel(String certType) {
        return switch (certType) {
            case "REGISTER" -> "登记证";
            case "DRIVING" -> "行驶证";
            case "INSURANCE" -> "交强险";
            case "INSPECT" -> "年检";
            default -> certType;
        };
    }
}
