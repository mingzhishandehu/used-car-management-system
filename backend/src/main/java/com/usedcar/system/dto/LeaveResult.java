package com.usedcar.system.dto;

/** 办理离职的结果：给前端弹「已把 N 辆车流转给 XXX」。 */
public record LeaveResult(
        Long employeeId,
        Long successorId,
        String successorName,
        int vehicleCount
) {
}
