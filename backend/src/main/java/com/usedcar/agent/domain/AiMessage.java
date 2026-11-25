package com.usedcar.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * AI 收车消息，对应 ai_message 表。长期保存，不删。
 *
 * card_json 是发这条消息那一刻的卡片快照，不是指向当前草稿的引用。
 * 必须存快照：卡片后面会被改、会被确认变灰，如果历史消息只存一个 id，
 * 回放时看到的全是最终版，就还原不出「当时 AI 抽到了什么、老板当时确认了什么」。
 */
@Data
@Accessors(chain = true)
@TableName("ai_message")
public class AiMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** USER 老板 / ASSISTANT AI */
    private String role;

    /** 纯文本，不渲染 markdown，也不需要富文本 */
    private String content;

    /** 该消息附带的卡片快照 JSON；不带卡片则为 null */
    private String cardJson;

    private LocalDateTime createdAt;
}
