package com.usedcar.approval.dto;

/** 审批动作入参。批准时 remark 可空（默认「同意」），驳回时必填。 */
public record ApprovalActionRequest(String remark) {
}
