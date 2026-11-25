package com.usedcar.approval.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 申请单出参。payload 反序列化成 Map 给前端「复制重提」直接预填表单。
 * 申请人和审批人的姓名在这里带出，前端不用再为每行发一次请求。
 */
public record ApprovalVO(
        Long id,
        String operationType,
        String operationLabel,
        String source,
        Long targetVehicleId,
        Map<String, Object> payload,
        Long applicantId,
        String applicantName,
        String status,
        Long approverId,
        String approverName,
        String approveRemark,
        Long resultVehicleId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
