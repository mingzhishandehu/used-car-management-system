package com.usedcar.agent.dto;

import java.time.LocalDateTime;

/**
 * 消息流里的一条。
 * card 是这条消息当时附带的卡片快照，可能为 null（例如老板发的纯文本）。
 */
public record MessageVO(
        Long id,
        String role,
        String content,
        IntakeCardDTO card,
        LocalDateTime createdAt
) {
}
