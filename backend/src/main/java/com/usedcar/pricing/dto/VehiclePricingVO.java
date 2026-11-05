package com.usedcar.pricing.dto;

import java.math.BigDecimal;

/**
 * 车辆定价测算结果。
 *
 * 一个对象把「这台车现在值多少钱、压了多少钱、该不该降」讲完整，
 * 前端详情页直接照着渲染，不用自己在页面上做减法——金额计算放在前端，迟早两边算得不一样。
 */
public record VehiclePricingVO(

        Long vehicleId,
        String stockNo,
        String status,

        /** 成本合计（收车款 + 整备 + 运输 + 过户 + 其他） */
        BigDecimal totalCost,

        /** 持有天数，从收车日期算到今天 */
        long holdingDays,

        /** 资金成本：总成本 × 年化利率 × 持有天数 / 365 */
        BigDecimal capitalCost,

        /** 保本底价：总成本 × (1 + 加成) + 固定额，低于它卖出就是亏 */
        BigDecimal breakevenPrice,

        /** 首次挂牌价，阶梯降价的基准 */
        BigDecimal basePrice,

        /** 当前挂牌价 */
        BigDecimal listPrice,

        /** 当前命中的阶梯降价幅度，0.03 / 0.08 / 0.13 / 0 */
        BigDecimal ladderRate,

        /** 按阶梯算出的建议挂牌价 */
        BigDecimal suggestedPrice,

        /** 以当前挂牌价卖出、扣掉总成本和资金成本后的预计毛利 */
        BigDecimal expectedProfit,

        /** 当前挂牌价是否已低于保本底价 */
        boolean belowBreakeven,

        /** 滞销等级：NORMAL / ATTENTION / WARN / DANGER */
        String slowLevel,

        /** 滞销等级的中文说明，例如「严重滞销（持有 82 天）」 */
        String slowLevelLabel
) {
}
