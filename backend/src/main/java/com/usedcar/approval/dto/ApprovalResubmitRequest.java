package com.usedcar.approval.dto;

import java.util.Map;

/**
 * 复制重提入参。只传表单里改过的键即可，
 * 未出现的键沿用原申请快照（例如 ownerUserId 归属字段）。
 */
public record ApprovalResubmitRequest(Map<String, Object> payload) {
}
