package com.usedcar.pricing;

import com.usedcar.common.BizException;
import com.usedcar.pricing.dto.VehiclePricingVO;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.mapper.VehicleCostMapper;
import com.usedcar.vehicle.mapper.VehicleMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 定价测算。
 *
 * 一条硬规矩：这个方法里不允许出现任何业务数字（0.08、2000、45、0.03 ……），
 * 全部从 PricingRuleService 的快照里取。想在代码里看到"年化 8%"这种字面量，只能去 pricing_rule 表里看。
 */
@Service
public class PricingService {

    private final VehicleMapper vehicleMapper;
    private final VehicleCostMapper vehicleCostMapper;
    private final PricingRuleService pricingRuleService;

    public PricingService(VehicleMapper vehicleMapper,
                          VehicleCostMapper vehicleCostMapper,
                          PricingRuleService pricingRuleService) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleCostMapper = vehicleCostMapper;
        this.pricingRuleService = pricingRuleService;
    }

    /**
     * 测算某辆车的定价。
     *
     * @param costOverride 外部已经算好的成本合计；传 null 表示由本方法自己查库。
     *                     列表页批量算的时候，成本可以一次 GROUP BY 查出来传进来，避免 N+1。
     */
    public VehiclePricingVO calculate(Vehicle vehicle, BigDecimal costOverride) {
        PricingRuleService.RuleSet r = pricingRuleService.rules();

        BigDecimal totalCost = costOverride != null
                ? costOverride
                : vehicleCostMapper.sumCost(vehicle.getId());
        if (totalCost == null) {
            totalCost = BigDecimal.ZERO;
        }

        // 持有天数：收车日期 → 今天。未来日期（录错了）按 0 天算，不产生负的资金成本
        long holdingDays = Math.max(0, ChronoUnit.DAYS.between(vehicle.getPurchaseDate(), LocalDate.now()));

        // 资金成本 = 总成本 × 年化 × 天数 / 365，保留 2 位（四舍五入）
        BigDecimal capitalCost = totalCost
                .multiply(r.capitalAnnualRate())
                .multiply(BigDecimal.valueOf(holdingDays))
                .divide(BigDecimal.valueOf(r.capitalDaysBase()), 2, RoundingMode.HALF_UP);

        // 保本底价 = 总成本 × (1 + 加成) + 固定额
        BigDecimal breakevenPrice = totalCost
                .multiply(BigDecimal.ONE.add(r.breakevenMarkup()))
                .add(r.breakevenFixed())
                .setScale(2, RoundingMode.HALF_UP);

        // 阶梯降价：按持有天数命中最高一档，只降一次，不是逐档累加
        BigDecimal ladderRate = BigDecimal.ZERO;
        if (holdingDays >= r.tier3Days()) {
            ladderRate = r.tier3Rate();
        } else if (holdingDays >= r.tier2Days()) {
            ladderRate = r.tier2Rate();
        } else if (holdingDays >= r.tier1Days()) {
            ladderRate = r.tier1Rate();
        }

        // 降价基准优先用 basePrice（首次挂牌价），没有才退回当前挂牌价
        BigDecimal priceBase = vehicle.getBasePrice() != null ? vehicle.getBasePrice() : vehicle.getListPrice();
        BigDecimal suggestedPrice = priceBase == null
                ? null
                : priceBase.multiply(BigDecimal.ONE.subtract(ladderRate)).setScale(2, RoundingMode.HALF_UP);

        BigDecimal listPrice = vehicle.getListPrice();
        BigDecimal expectedProfit = listPrice == null
                ? null
                : listPrice.subtract(totalCost).subtract(capitalCost).setScale(2, RoundingMode.HALF_UP);

        boolean belowBreakeven = listPrice != null && listPrice.compareTo(breakevenPrice) < 0;

        String slowLevel;
        if (holdingDays >= r.slowDangerDays()) {
            slowLevel = "DANGER";
        } else if (holdingDays >= r.slowWarnDays()) {
            slowLevel = "WARN";
        } else if (holdingDays >= r.slowAttentionDays()) {
            slowLevel = "ATTENTION";
        } else {
            slowLevel = "NORMAL";
        }

        return new VehiclePricingVO(
                vehicle.getId(), vehicle.getStockNo(), vehicle.getStatus(),
                totalCost.setScale(2, RoundingMode.HALF_UP), holdingDays, capitalCost, breakevenPrice,
                vehicle.getBasePrice(), listPrice, ladderRate, suggestedPrice, expectedProfit,
                belowBreakeven, slowLevel, slowLabel(slowLevel, holdingDays));
    }

    /**
     * 按 id 查了再算，给详情接口用。
     */
    public VehiclePricingVO calculateByVehicleId(Long vehicleId) {
        Vehicle vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            throw new BizException(404, "车辆不存在");
        }
        return calculate(vehicle, null);
    }

    private String slowLabel(String level, long days) {
        return switch (level) {
            case "DANGER" -> "严重滞销（持有 " + days + " 天）";
            case "WARN" -> "滞销（持有 " + days + " 天）";
            case "ATTENTION" -> "滞销关注（持有 " + days + " 天）";
            default -> "正常（持有 " + days + " 天）";
        };
    }
}
