package com.usedcar.agent.dto;

import java.util.List;

/**
 * 会话详情。
 * messages 是完整历史（长期保存，不截断）；card 是「当前那张可操作的卡片」，
 * 前端只把最后一条带卡片的 assistant 消息渲染成可编辑态，其余快照一律只读置灰。
 */
public record ConversationDetailVO(
        ConversationVO conversation,
        List<MessageVO> messages,
        IntakeCardDTO card
) {
}
