package com.usedcar.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.agent.client.SiliconFlowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 走硅基流动 deepseek-ai/DeepSeek-V4.1-Flash 的抽取器（默认）。
 *
 * 关键设计：模型只被要求「输出增量字段 + 一句人话」，不让它决定要不要写库、
 * 也不让它返回完整车辆对象。原因很简单——模型是不可信输入，
 * 一旦允许它直接产出「待插入记录」，就等于把业务校验交给了概率。
 * 真正的白名单过滤、类型归一化、必填判定、写库，全在 Java 侧。
 */
@Service
@ConditionalOnProperty(name = "usedcar.ai.provider", havingValue = "siliconflow", matchIfMissing = true)
public class SiliconFlowExtractor implements IntakeExtractor {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowExtractor.class);

    private static final String SYSTEM_PROMPT = """
            你是二手车门店的收车信息录入助手。老板用口语描述刚收到的一辆车，你负责把信息抽成结构化字段。

            只输出一个 JSON 对象，不要输出解释文字，不要加 markdown 代码块围栏。格式固定为：
            {"fields": {"brand": "大众"}, "reply": "还缺收车日期，麻烦补一下"}

            字段清单（key 必须完全一样，不要自造字段）：
            brand          品牌          文本，必填
            model          车型          文本，必填
            purchasePrice  收车价        数字，单位元，必填。"6.8万" 写成 68000，"六万八" 也写成 68000
            purchaseDate   收车日期      YYYY-MM-DD，必填。"今天" 必须换成下面给的具体日期，"昨天" 换算成前一天，不要留空
            vin            VIN 车架号    文本，建议补充
            firstRegDate   首次上牌日期  YYYY-MM-DD，建议补充
            mileage        表显里程      数字，单位 km，建议补充。"6.2万公里" 写成 62000
            plateNo        车牌号        文本，建议补充
            listPrice      挂牌价        数字，单位元，可空
            remark         备注          文本，可空

            规则：
            1. fields 只放这一轮新抽到、或老板明确要改的字段，是增量。没提到的字段不要输出，也不要重复输出已有字段。
            2. 老板说「改成 X」「不对，是 X」「收车价写 7 万」时，输出被修改的字段和新值。
            3. 老板说「跳过」「先这样」「不用了」时，fields 输出空对象 {}。
            4. 绝不编造。没听到就别写；金额、日期、里程有歧义时宁可不填。
            5. 数字字段里不要出现「万」「元」「km」、逗号或任何单位与标点。
            6. reply 一句中文口语，说明这轮抽到了什么、还缺点什么，不超过 40 字。
            7. reply 里只写中文，禁止出现 brand、model、vin 这类英文字段名，要说「品牌」「车型」「车架号」。
            8. 老板说「今天收的」「昨天收的」时，purchaseDate 必须填成换算后的具体日期，
               不能因为没听到数字就留空。下面给了今天的日期和昨天的日期，直接照抄。
            """;

    private final SiliconFlowClient client;
    private final ObjectMapper objectMapper;

    public SiliconFlowExtractor(SiliconFlowClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExtractionResult extract(ExtractionContext context) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(message("system", SYSTEM_PROMPT
                + "\n今天是 " + context.today() + "，昨天是 " + context.today().minusDays(1) + "。"
                + "\n当前卡片草稿（JSON，可能为空）：" + toJson(context.draft())));

        for (HistoryMessage h : context.history()) {
            messages.add(message(h.openAiRole(), h.content()));
        }
        messages.add(message("user", context.userInput()));

        String content = client.chat(messages);
        return parse(content);
    }

    /** 模型偶尔会带 ```json 围栏或多说一句，这里做一次兜底，不让整轮对话白费。 */
    private ExtractionResult parse(String content) {
        try {
            String json = stripToJsonObject(content);
            JsonNode root = objectMapper.readTree(json);
            Map<String, Object> fields = new LinkedHashMap<>();
            JsonNode fieldsNode = root.path("fields");
            if (fieldsNode.isObject()) {
                fieldsNode.fields().forEachRemaining(e -> {
                    Object value = scalar(e.getValue());
                    if (value != null) {
                        fields.put(e.getKey(), value);
                    }
                });
            }
            String reply = root.path("reply").asText(null);
            return new ExtractionResult(fields, reply);
        } catch (Exception e) {
            // 返回了内容但不是合法 JSON：记日志、卡片保持原样，让老板重说一遍。
            // 不抛 500，否则模型一次格式抖动就把整个会话打断。
            log.warn("模型返回非 JSON 内容，本轮忽略，content={}", abbreviate(content));
            return new ExtractionResult(Map.of(), "这句我没解析出来，麻烦换个说法再说一遍");
        }
    }

    private static String stripToJsonObject(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static Object scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }

    private String toJson(Map<String, Object> draft) {
        try {
            return objectMapper.writeValueAsString(draft == null ? Map.of() : draft);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, String> message(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
