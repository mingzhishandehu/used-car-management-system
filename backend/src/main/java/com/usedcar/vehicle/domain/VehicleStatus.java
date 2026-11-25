package com.usedcar.vehicle.domain;

import com.usedcar.common.BizException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 车辆状态机。
 *
 * 为什么用枚举 + 白名单，而不是在 Service 里写 if 判断：
 *   1. 合法流转只有一份定义，Controller、Service、定时任务都读同一份，不会各写一套。
 *   2. 非法流转会在这里被明确拒绝，而不是靠「大家都记得不能这么改」。
 *   3. 面试问「你怎么保证状态不乱」，答案是「状态机收口在枚举里，流转必须过 canTransitionTo」。
 *
 * 流转图：
 *   PURCHASED 已收车 → ON_SALE 在售 ↔ SOLD 已成交 → AFTER_SALE 售后 ↔ SOLD 已成交 → CLEARED 已清库
 *
 * 注意：这里只描述「允许的下一步」，具体业务条件（例如「售后未结案不许清库」）
 * 由 Service 层额外校验，枚举只管骨架合法性。
 */
public enum VehicleStatus {

    /** 已收车：钱付了，车在库里，还没挂牌 */
    PURCHASED("已收车"),
    /** 在售：已挂牌，有对外报价 */
    ON_SALE("在售"),
    /** 已成交：签了订单、收了钱 */
    SOLD("已成交"),
    /** 售后：交付后进入售后跟踪期 */
    AFTER_SALE("售后"),
    /** 已清库：售后结案、成本核算完成，退出库存 */
    CLEARED("已清库");

    private final String label;

    VehicleStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 允许的流转表。
     * static 块里一次性建好，枚举加载时执行一次，之后只读，天然线程安全。
     */
    private static final Map<VehicleStatus, Set<VehicleStatus>> ALLOWED;

    static {
        Map<VehicleStatus, Set<VehicleStatus>> map = new EnumMap<>(VehicleStatus.class);
        // 收车后只能挂牌，不能直接成交——必须先在售，否则挂牌价和调价记录都断链
        map.put(PURCHASED, EnumSet.of(ON_SALE));
        // 在售可以成交；也可以直接清库（例如车况重大事故，转批发处理）
        map.put(ON_SALE, EnumSet.of(SOLD, CLEARED));
        // 成交撤回后回到在售，允许纠正误成交或订单未成立的情况
        map.put(SOLD, EnumSet.of(ON_SALE, AFTER_SALE, CLEARED));
        // 成交后可能进售后，也可能客户当场提走无售后，直接清库
        // 售后记录有误或问题已撤销时，可退回已成交
        map.put(AFTER_SALE, EnumSet.of(SOLD, CLEARED));
        // 清库后只允许按最近状态流水撤回到清库前状态，具体目标由 Service 校验
        map.put(CLEARED, EnumSet.of(ON_SALE, SOLD, AFTER_SALE));
        ALLOWED = Collections.unmodifiableMap(map);
    }

    /**
     * 判断当前状态能否流转到目标状态。
     */
    public boolean canTransitionTo(VehicleStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    /**
     * 校验流转，不合法直接抛业务异常。
     * 返回 this 是为了支持链式写法，本阶段没用到，保留语义清晰。
     */
    public VehicleStatus requireTransitionTo(VehicleStatus target) {
        if (!canTransitionTo(target)) {
            throw new BizException(400,
                    String.format("车辆状态不允许从「%s」流转到「%s」", this.getLabel(), target.getLabel()));
        }
        return this;
    }
}
