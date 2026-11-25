package com.usedcar.agent.dto;

import java.util.List;
import java.util.Map;

/**
 * 内嵌在聊天消息流里的那张卡片。
 *
 * status = DRAFT 时卡片可编辑；CONFIRMED 时前端整张置灰只读，
 * 但字段值原样保留，聊天记录里还能看清当时确认的是什么。
 */
public record IntakeCardDTO(
        String status,
        Map<String, Object> fields,
        List<FieldSpecDTO> spec,
        List<String> missingRequired,
        List<String> missingOptional,
        boolean skipOptional,
        boolean readyToConfirm,
        Long vehicleId,
        String stockNo
) {
}
