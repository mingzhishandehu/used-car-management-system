package com.usedcar.agent.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 车辆信息抽取器。AI 收车链路里唯一「可替换的零件」。
 *
 * 为什么要接口：模型的选型、prompt、供应商都会变，但「多轮补全 → 卡片确认 → 写库」
 * 这条编排不该跟着变。现在有两个实现：
 *   SiliconFlowExtractor  —— 硅基流动真实模型（默认）
 *   MockIntakeExtractor   —— 本地关键词，没配 Key 时联调用
 * 由 usedcar.ai.provider 条件注入，切换只改 .env，不动一行业务代码。
 */
public interface IntakeExtractor {

    ExtractionResult extract(ExtractionContext context);

    /** 发给模型的上下文。draft 是当前卡片，history 是最近若干轮对话。 */
    record ExtractionContext(
            Map<String, Object> draft,
            List<HistoryMessage> history,
            String userInput,
            LocalDate today
    ) {
    }

    record HistoryMessage(String role, String content) {

        /**
         * 库里的角色名（USER / ASSISTANT / SYSTEM）转成 OpenAI 的 role。
         *
         * SYSTEM 是摘要压缩引入的：摘要必须作为 system 消息进上下文，
         * 混成 assistant 会让模型以为「上一轮自己说过这段总结」。
         */
        public String openAiRole() {
            return switch (role) {
                case "USER" -> "user";
                case "SYSTEM" -> "system";
                default -> "assistant";
            };
        }
    }

    /**
     * 抽取结果。
     * fields 只放「这一轮新抽到或要改的字段」，是增量而不是全量；
     * 合并与清洗由 AiIntakeService 统一做，抽取器不碰卡片状态。
     * reply 是给老板看的一句话，可能为空，由编排层补兜底文案。
     */
    record ExtractionResult(Map<String, Object> fields, String reply) {
    }
}
