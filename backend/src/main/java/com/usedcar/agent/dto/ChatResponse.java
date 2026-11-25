package com.usedcar.agent.dto;

/** 一次对话的返回：AI 的追问文字 + 更新后的卡片。 */
public record ChatResponse(
        Long conversationId,
        String reply,
        IntakeCardDTO card
) {
}
