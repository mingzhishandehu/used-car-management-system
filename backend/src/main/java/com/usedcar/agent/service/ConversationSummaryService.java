package com.usedcar.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.agent.client.SiliconFlowClient;
import com.usedcar.agent.config.AiProperties;
import com.usedcar.agent.domain.AiConversation;
import com.usedcar.agent.domain.AiMessage;
import com.usedcar.agent.mapper.AiConversationMapper;
import com.usedcar.agent.mapper.AiMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 会话摘要压缩：把早期消息压成一段文字，替代原来的「硬截最近 N 条」。
 *
 * 为什么必须异步：
 *   压缩要调一次模型，几秒钟。放在 chat() 的主事务里会让这条数据库连接
 *   一直挂着等 HTTP 返回，并发几个会话就能把 Hikari 池（上限 10）拖空。
 *   所以由 AiIntakeService 在事务提交后触发，本类在自己的线程和新事务里跑。
 *
 * 为什么失败必须吞掉：
 *   压缩是「让下次对话更聪明」的优化，不是本轮对话的必要条件。
 *   模型超时、返回格式不对，都只记日志，绝不能影响老板这次收车。
 */
@Service
public class ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    private static final String PROMPT = """
            你是二手车门店的对话压缩器。把下面的收车对话压成不超过 200 字的中文摘要。

            只保留对后续对话有用的信息：
            1. 已经确认的车辆字段：品牌、车型、收车价、收车日期、里程、车牌、VIN；
            2. 老板改过什么（例如「收车价从 6.8 万改成 7 万」）；
            3. 卡片当前状态（待确认 / 已提交审批 / 已录入 / 已放弃）；
            4. 聊过哪些数据查询主题（例如「问过库存台数、问了均价」）。

            不要保留寒暄、不要重复、不要编造对话里没出现的信息。
            如果已有摘要，把新内容并进去，不要丢掉旧摘要里的车辆字段。
            只输出 JSON：{"summary":"..."}，不要解释文字，不要 markdown 围栏。
            """;

    private final AiMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;
    private final SiliconFlowClient client;
    private final AiProperties props;
    private final ObjectMapper objectMapper;

    public ConversationSummaryService(AiMessageMapper messageMapper,
                                      AiConversationMapper conversationMapper,
                                      SiliconFlowClient client,
                                      AiProperties props,
                                      ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.client = client;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 事务提交后调用。未摘要消息不足阈值就直接返回，不产生任何模型调用。
     *
     * @Async 让调用方立刻返回；REQUIRES_NEW 保证在新线程里拿到独立事务，
     * 不会去等主线程那个已经提交/正在提交的事务。
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void compressAsync(Long conversationId) {
        if (conversationId == null || !props.hasApiKey()) {
            return;
        }
        try {
            doCompress(conversationId);
        } catch (Exception e) {
            // 异步方法抛异常没人接，必须自己吞掉并记日志
            log.warn("会话 {} 摘要压缩失败，继续用原始历史: {}", conversationId, e.getMessage());
        }
    }

    private void doCompress(Long conversationId) {
        AiConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            return;
        }
        long after = conv.getSummarizedMsgId() == null ? 0L : conv.getSummarizedMsgId();

        Long pending = messageMapper.selectCount(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .gt(AiMessage::getId, after));
        if (pending == null || pending < props.summaryTriggerOrDefault()) {
            return;
        }

        // 只吃最早的一批，剩下的留给下次：一次压缩太多会让摘要丢掉细节
        List<AiMessage> batch = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .gt(AiMessage::getId, after)
                .orderByAsc(AiMessage::getId)
                .last("LIMIT " + props.summaryBatchOrDefault()));
        if (batch.isEmpty()) {
            return;
        }

        String summary = summarize(conv.getSummaryText(), batch);
        if (summary == null || summary.isBlank()) {
            return;
        }

        Long lastId = batch.get(batch.size() - 1).getId();
        conversationMapper.updateById(new AiConversation()
                .setId(conversationId)
                .setSummaryText(summary)
                .setSummarizedMsgId(lastId));
        log.info("会话 {} 压缩 {} 条消息，摘要 {} 字，覆盖到 msg {}", conversationId, batch.size(),
                summary.length(), lastId);
    }

    private String summarize(String oldSummary, List<AiMessage> batch) {
        StringBuilder sb = new StringBuilder();
        for (AiMessage m : batch) {
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            sb.append("USER".equals(m.getRole()) ? "老板：" : "助手：")
                    .append(clip(m.getContent(), 150))
                    .append('\n');
        }
        String previous = oldSummary == null || oldSummary.isBlank() ? "（无）" : oldSummary;
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", PROMPT),
                Map.of("role", "user", "content",
                        "已有摘要：" + previous + "\n\n需要并入摘要的对话：\n" + sb));

        JsonNode root = readJson(client.chat(messages));
        return root == null ? null : root.path("summary").asText(null);
    }

    private JsonNode readJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("摘要不是合法 JSON，本轮放弃: {}", text.length() > 200 ? text.substring(0, 200) : text);
            return null;
        }
    }

    private static String clip(String s, int max) {
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
