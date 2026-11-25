package com.usedcar.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.agent.config.AiProperties;
import com.usedcar.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 硅基流动 OpenAI 兼容接口客户端。
 *
 * 用 Spring 6 自带的 RestClient，不引第三方 SDK：
 * spring-boot-starter-web 已经在 classpath 里，多引一个 SDK 只会多一份版本冲突风险。
 * 超时必须显式给：默认 RestClient 用 JDK 的无限读超时，模型卡住会把 Tomcat 线程挂死。
 *
 * 三个出口，职责分层：
 *   chat(messages)                      —— 只要文本，抽取器和摘要用
 *   chat(messages, tools)               —— 要完整 message 节点，意图路由用（可能返回 tool_calls）
 *   chatStream(messages, onDelta)       —— 流式吐增量，聊天页打字机效果用
 * 业务字段的解析一律不在这里做，换供应商时不动编排层。
 */
@Component
public class SiliconFlowClient {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowClient.class);

    /** 模型返回的一条消息：要么是文本，要么是一批待执行的工具调用，不会同时有意义。 */
    public record Message(String content, List<ToolCall> toolCalls) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /** 一次工具调用请求。arguments 是 JSON 字符串，不是对象，调用方必须自己 parse。 */
    public record ToolCall(String id, String name, String arguments) {
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AiProperties props;

    public SiliconFlowClient(AiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeoutMs());
        factory.setReadTimeout(props.readTimeoutMs());

        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 只要文本的一次调用。抽取器、摘要压缩走这里。
     *
     * @param messages OpenAI 格式的消息列表，元素形如 {"role":"user","content":"..."}
     */
    public String chat(List<Map<String, String>> messages) {
        return chat(toObjectMessages(messages), null).content();
    }

    /**
     * 带 tools 声明的一次调用，返回完整 message 节点。
     *
     * 注意 response_format 与 tools 不能同时发：OpenAI 兼容实现里两者互斥，
     * 同时给会被上游拒。所以带 tools 时强制走 tool_calls，不带时才开 JSON Mode。
     */
    public Message chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        boolean withTools = tools != null && !tools.isEmpty();
        Map<String, Object> body = baseBody(messages);
        if (withTools) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        } else {
            body.put("response_format", Map.of("type", "json_object"));
        }
        String raw = post(body);
        return parseMessage(raw);
    }

    /**
     * 流式调用：每收到一段增量就回调一次，最后返回拼接好的完整文本。
     *
     * 为什么要边读边回调：上游是 SSE，一行一个 data: {...}，把整段读完再返回
     * 就等于没做流式。这里用 RestClient.exchange 直接拿 InputStream，
     * 逐行解析 delta.content。
     */
    public String chatStream(List<Map<String, Object>> messages, Consumer<String> onDelta) {
        requireKey();
        Map<String, Object> body = baseBody(messages);
        body.put("stream", true);

        StringBuilder full = new StringBuilder();
        try {
            restClient.post()
                    .uri("/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey().trim())
                    .body(body)
                    .exchange((request, response) -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) {
                                    continue;
                                }
                                String data = line.substring(5).trim();
                                if (data.isEmpty() || "[DONE]".equals(data)) {
                                    continue;
                                }
                                String delta = readDelta(data);
                                if (!delta.isEmpty()) {
                                    full.append(delta);
                                    onDelta.accept(delta);
                                }
                            }
                        }
                        return null;
                    });
            return full.toString();
        } catch (RestClientResponseException e) {
            log.warn("硅基流动流式请求被拒，status={}, model={}", e.getStatusCode(), props.model());
            throw new BizException(502, "AI 服务拒绝请求，请检查模型 ID 与密钥是否正确");
        } catch (ResourceAccessException e) {
            log.warn("硅基流动流式连接超时: {}", e.getMessage());
            throw new BizException(504, "AI 服务连接超时，请稍后重试");
        } catch (RestClientException e) {
            log.error("硅基流动流式调用失败", e);
            throw new BizException(502, "AI 服务调用失败");
        } catch (Exception e) {
            log.error("解析流式响应失败", e);
            throw new BizException(502, "AI 服务返回格式异常");
        }
    }

    // ==================== 内部 ====================

    private Map<String, Object> baseBody(List<Map<String, Object>> messages) {
        requireKey();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("messages", messages);
        // 0.1 是刻意压低的：这个场景要的是稳定抽取和稳定 SQL，不需要发挥
        body.put("temperature", 0.1);
        body.put("max_tokens", 1024);
        return body;
    }

    private void requireKey() {
        if (!props.hasApiKey()) {
            // 明确报错，不静默降级成 mock。演示时「看起来能跑但其实是假数据」最坑。
            throw new BizException(503, "未配置 AI 密钥：请在 .env 里设置 USEDCAR_AI_API_KEY");
        }
    }

    private String post(Map<String, Object> body) {
        requireKey();
        try {
            return restClient.post()
                    .uri("/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey().trim())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (BizException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // 不把上游响应体原文返回给前端：里面可能有账号信息、内部路由
            log.warn("硅基流动返回异常，status={}, model={}", e.getStatusCode(), props.model());
            throw new BizException(502, "AI 服务拒绝请求，请检查模型 ID 与密钥是否正确");
        } catch (ResourceAccessException e) {
            log.warn("硅基流动连接超时或网络不可达: {}", e.getMessage());
            throw new BizException(504, "AI 服务连接超时，请稍后重试");
        } catch (RestClientException e) {
            log.error("硅基流动调用失败", e);
            throw new BizException(502, "AI 服务调用失败");
        }
    }

    private Message parseMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(502, "AI 服务返回空响应");
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new BizException(502, "AI 服务返回格式异常：缺少 choices");
            }
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").isNull() ? null : message.path("content").asText(null);

            List<ToolCall> calls = new ArrayList<>();
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode node : toolCalls) {
                    String id = node.path("id").asText("");
                    String name = node.path("function").path("name").asText("");
                    String args = node.path("function").path("arguments").asText("{}");
                    if (!name.isBlank()) {
                        calls.add(new ToolCall(id, name, args));
                    }
                }
            }
            if ((content == null || content.isBlank()) && calls.isEmpty()) {
                throw new BizException(502, "AI 服务返回了空内容");
            }
            return new Message(content, calls);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 AI 响应失败", e);
            throw new BizException(502, "AI 服务返回格式异常");
        }
    }

    /** 从一行 SSE data 里取 delta.content。上游偶尔会塞空的 heartbeat，忽略即可。 */
    private String readDelta(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode choices = node.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return "";
            }
            JsonNode delta = choices.get(0).path("delta");
            return delta.path("content").isNull() ? "" : delta.path("content").asText("");
        } catch (Exception e) {
            log.debug("跳过无法解析的流式分片: {}", data.length() > 120 ? data.substring(0, 120) : data);
            return "";
        }
    }

    /** 把 List<Map<String,String>> 放宽成 List<Map<String,Object>>，避免调用方到处转型。 */
    private List<Map<String, Object>> toObjectMessages(List<Map<String, String>> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (Map<String, String> m : messages) {
            out.add(new LinkedHashMap<>(m));
        }
        return out;
    }
}
