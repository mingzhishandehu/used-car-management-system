package com.usedcar.video.copy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.vehicle.domain.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 短视频口播文案生成。
 *
 * 有 API Key 就走 DeepSeek 真实改写；没有就用本地模板兜底。
 * 这不是"偷懒"，是刻意的降级设计：AI 调用失败不能让整条视频流水线停摆，
 * 文案差一点也比任务失败强。真实系统里这类降级通常还要再配一层熔断。
 *
 * 用 JDK 自带的 HttpClient 而不是引 Spring AI：
 *   当前只用一个 chat/completions 接口，引一整套框架会增加依赖面和版本冲突风险。
 *   等真的要做多模型路由、function calling、结构化输出时再换，那时框架才划算。
 */
@Component
public class CopyWriter {

    private static final Logger log = LoggerFactory.getLogger(CopyWriter.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 单次改写超时。写文案不是实时交互，给到 25 秒足够，超时就走兜底 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public CopyWriter(
            @Value("${app.ai.deepseek.api-key:${DEEPSEEK_API_KEY:}}") String apiKey,
            @Value("${app.ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${app.ai.deepseek.model:deepseek-chat}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /**
     * 生成口播文案。
     *
     * @param vehicle    车辆，用来拼事实（车型、里程、年份）
     * @param sourceText 销售录入的卖点原文，可以为空
     * @return 3~4 句、总长控制在 60 字以内的口播稿
     */
    public String generate(Vehicle vehicle, String sourceText) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("未配置 DeepSeek API Key，使用本地模板文案");
            return fallback(vehicle, sourceText);
        }

        String prompt = buildPrompt(vehicle, sourceText);
        try {
            String result = callDeepSeek(prompt);
            if (result != null && !result.isBlank()) {
                return result.trim();
            }
            log.warn("DeepSeek 返回空内容，使用本地模板文案");
        } catch (Exception e) {
            // 关键：这里必须吞掉异常走兜底，不能让一次 AI 抖动把整个视频任务打成失败
            log.warn("DeepSeek 文案生成失败，降级为本地模板: {}", e.getMessage());
        }
        return fallback(vehicle, sourceText);
    }

    private String buildPrompt(Vehicle vehicle, String sourceText) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是二手车门店的短视频文案，写一段口播稿。要求：\n")
                .append("1. 只输出文案正文，不要标题、不要解释、不要引号；\n")
                .append("2. 3 到 4 句，总字数不超过 60 字；\n")
                .append("3. 口语化，适合念出来，不要营销套话；\n")
                .append("4. 不要编造事实，只能使用下面给出的信息。\n\n")
                .append("车辆信息：\n")
                .append("品牌车型：").append(vehicle.getBrand()).append(' ').append(vehicle.getModel()).append('\n');
        if (vehicle.getFirstRegDate() != null) {
            sb.append("上牌年份：").append(vehicle.getFirstRegDate().getYear()).append(" 年\n");
        }
        if (vehicle.getMileage() != null) {
            sb.append("表显里程：").append(vehicle.getMileage()).append(" 公里\n");
        }
        if (sourceText != null && !sourceText.isBlank()) {
            sb.append("销售补充卖点：").append(sourceText).append('\n');
        }
        return sb.toString();
    }

    private String callDeepSeek(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是专业的二手车短视频文案，只输出文案正文。"),
                        Map.of("role", "user", "content", prompt)),
                "temperature", 0.7,
                "max_tokens", 200);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return content.isMissingNode() ? null : content.asText();
    }

    /**
     * 本地兜底文案。结构跟真实文案一致，保证后面的字幕切分、时长估算不用做特殊分支。
     */
    private String fallback(Vehicle vehicle, String sourceText) {
        StringBuilder sb = new StringBuilder();
        sb.append(vehicle.getFirstRegDate() == null ? "" : vehicle.getFirstRegDate().getYear() + "年 ")
                .append(vehicle.getBrand()).append(' ').append(vehicle.getModel()).append("。");
        if (vehicle.getMileage() != null) {
            sb.append("表显 ").append(String.format("%,d", vehicle.getMileage())).append(" 公里。");
        }
        if (sourceText != null && !sourceText.isBlank()) {
            sb.append(sourceText).append("。");
        }
        sb.append("支持第三方检测，支持贷款，到店即可提车。");
        return sb.toString();
    }
}
