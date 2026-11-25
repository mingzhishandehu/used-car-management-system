package com.usedcar.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 收车的外部配置。
 *
 * 用 record 做构造器绑定：字段全 final，启动后不会被改，
 * 避免运行期有人 setApiKey 之后排查不出配置到底被谁动过。
 *
 * 对应关系见 application.yml 的 usedcar.ai 段。apiKey 只从环境变量
 * USEDCAR_AI_API_KEY 注入，仓库里不存明文。
 */
@ConfigurationProperties(prefix = "usedcar.ai")
public record AiProperties(
        String baseUrl,
        String apiKey,
        String model,
        String provider,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxHistory,
        /** 单次提问最多允许几轮工具调用，超过就强制收口出答案。 */
        int maxToolRounds,
        /** 是否优先用 OpenAI tools 协议；关掉则全部走自定义 JSON 协议。 */
        boolean functionCalling,
        /** 未摘要消息超过多少条就触发一次压缩。 */
        int summaryTrigger,
        /** 一次压缩吃掉多少条最早的消息。 */
        int summaryBatch
) {
    public boolean isMock() {
        return "mock".equalsIgnoreCase(provider);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 配置漏填时给安全兜底，避免 0 轮工具调用、0 条摘要这类静默失效。 */
    public int maxToolRoundsOrDefault() {
        return maxToolRounds <= 0 ? 10 : maxToolRounds;
    }

    public int summaryTriggerOrDefault() {
        return summaryTrigger <= 0 ? 24 : summaryTrigger;
    }

    public int summaryBatchOrDefault() {
        return summaryBatch <= 0 ? 16 : summaryBatch;
    }
}
