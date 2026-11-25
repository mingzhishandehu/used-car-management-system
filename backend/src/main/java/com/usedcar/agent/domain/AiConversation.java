package com.usedcar.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * AI 收车会话，对应 ai_conversation 表。
 *
 * 一个会话对应「一辆待录入的车 + 一张卡片」。卡片数据放在 draft_json，
 * 不放单独的表：卡片本来就是会话级唯一的一份草稿，拆表只会多一次 join。
 *
 * draft_json / card_json 这类 MySQL JSON 列，Java 侧统一用 String 接：
 * MyBatis-Plus 默认没给 JSON 列配 TypeHandler，用 String 最稳，
 * 序列化交给 ObjectMapper，不引入额外的 typeHandler 配置。
 */
@Data
@Accessors(chain = true)
@TableName("ai_conversation")
public class AiConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话标题，取老板第一句话截断，列表页好认 */
    private String title;

    /** 发起人。会话只能本人读写，Controller 之前先校验，防止改 URL 翻别人记录 */
    private Long ownerUserId;

    /** 当前卡片草稿，JSON：{"brand":"大众", ...} */
    private String draftJson;

    /** NONE / DRAFT / CONFIRMED / DISCARDED */
    private String cardStatus;

    /** 1 = 老板点过「跳过建议字段」，硬必填齐了就允许确认 */
    private Integer skipOptional;

    /** 确认录入后生成的 vehicle.id；uk_conv_vehicle 保证一个会话不会产出两辆车 */
    private Long vehicleId;

    /**
     * 更早对话的压缩摘要。
     * 不做压缩时这个字段为 null，loadHistory 就只给最近 maxHistory 条；
     * 做过压缩后，摘要会作为一条 system 消息排在原始消息之前。
     */
    private String summaryText;

    /** 已被摘要覆盖的最后一条 ai_message.id。只压缩这个 id 之后的消息，避免重复压缩。 */
    private Long summarizedMsgId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
