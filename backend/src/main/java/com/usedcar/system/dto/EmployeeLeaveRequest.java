package com.usedcar.system.dto;

import jakarta.validation.constraints.NotNull;

/** 办理离职入参。接手人必填，原因可空（后端补默认值）。 */
public record EmployeeLeaveRequest(
        @NotNull(message = "必须指定接手员工") Long successorId,
        String reason
) {
}
