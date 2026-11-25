package com.usedcar.agent.dto;

/**
 * 确认录入的结果。
 * ADMIN 点确认直接写库：executed=true、vehicleId / stockNo 有值；
 * 员工点确认生成审批申请：executed=false、approvalId 有值、不产生车辆，
 * 卡片状态变成 PENDING（前端按非 DRAFT 一律置灰）。
 */
public record ConfirmResult(
        Long vehicleId,
        String stockNo,
        IntakeCardDTO card,
        boolean executed,
        Long approvalId
) {
}
