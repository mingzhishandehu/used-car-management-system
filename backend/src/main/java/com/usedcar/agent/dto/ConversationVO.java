package com.usedcar.agent.dto;

import java.time.LocalDateTime;

/** 左侧会话列表的一行。 */
public record ConversationVO(
        Long id,
        String title,
        String cardStatus,
        Long vehicleId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
