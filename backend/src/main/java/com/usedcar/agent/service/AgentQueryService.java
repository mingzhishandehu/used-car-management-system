package com.usedcar.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.agent.client.SiliconFlowClient;
import com.usedcar.agent.config.AiProperties;
import com.usedcar.agent.domain.AiAgentTrace;
import com.usedcar.agent.tool.ReadonlySqlTool;
import com.usedcar.agent.tool.SchemaCatalog;
import com.usedcar.agent.tool.ToolRegistry;
import com.usedcar.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 只读问答 Agent：意图路由 → 受控只读工具执行 → 结果回灌模型组织语言。
 *
 * 三个演进点，都在这一版里补掉了：
 *   1. 单轮改多轮：所有工具执行后回到路由，允许模型继续补齐证据
 *      （某品牌均价和全店均价对比这类问题必须两次查询），上限由 max-tool-rounds 控制。
 *   2. 自定义 JSON 协议改 OpenAI tools 协议：模型返回结构化的 tool_calls，
 *      不再靠 prompt 求它「输出合法 JSON」。解析失败或上游不支持时回落原协议。
 *   3. 数字校验从「整串判断」改成「逐数字找出处」，见 AnswerNumberGuard。
 *
 * 写权限仍然一层没动：没有任何写工具，收车写库仍是 Java 侧硬调用。
 */
@Service
public class AgentQueryService {

    private static final Logger log = LoggerFactory.getLogger(AgentQueryService.class);

    /** 没查到时的统一口径，不许模型自由发挥。 */
    private static final String UNKNOWN = AnswerNumberGuard.UNKNOWN;

    private static final String ROUTER_PROMPT = """
            你是二手车门店系统的数据查询路由器。判断老板这句话想干什么，只输出一个 JSON 对象，
            不要解释文字，不要 markdown 代码块围栏。

            高频固定工具（能精确回答时优先用）：
            %s

            通用只读查询（长尾问题必须用它）：
            %s

            业务库结构：
            %s

            已执行的查询记录（可能为空；为空表示这是第一轮）：
            %s

            输出格式五选一：
            1. 固定工具能精确回答：{"intent":"QUERY","tool":"固定工具名","args":{"参数名":"值"}}
            2. 固定工具算不出来：{"intent":"QUERY","tool":"run_readonly_sql","args":{"sql":"SELECT ..."}}
            3. 已有查询记录足够回答这个问题了：{"intent":"FINISH"}
            4. 在描述一辆要收的车、补充或修改收车信息：{"intent":"INTAKE"}
            5. 跟门店数据无关的闲聊：{"intent":"CHAT","reply":"一句中文回复"}

            规则：
            1. 【最重要】问题带时间/库龄/金额区间/均价/求和/分组/排名/跨表条件，而固定工具算不出来时，
               必须生成 SQL，不许拿相近的固定工具凑数。用「总台数」回答「超过一个月的有几台」是编数据。
            2. 生成 SQL 时只查业务库结构里列出的表，列名必须显式写，禁止 SELECT *，
               禁止任何写操作、注释、多语句。SQL 里用单条 SELECT 完成。
            3. 问「库存多少台」「在售几台」→ count_vehicles；「各状态分别多少」→ vehicle_status_summary；
               「按库存号/品牌查车」→ find_vehicle；「待审批多少」→ count_pending_approvals；
               「未结案售后」→ count_open_after_sales。
            4. 描述车辆信息（品牌、车型、收车价、里程、日期）或说「改成 X」→ INTAKE。
            5. 结合历史判断追问与指代。分析、对比、诊断、建议请求属于 QUERY；只有明确录入车辆才判 INTAKE。信息不足时用 CHAT 提出具体澄清问题。
            6. tool 必须是上面列出的名字之一，不许自造；args 只放该工具声明过的参数。
            7. 修改已有车辆或审批申请时，先查询确认目标及全部范围，再调用 propose_business_batch 提交操作清单。
               调价、车辆信息修改、状态流转、批准和驳回申请均走此工具。禁止写 SQL。
               即使当前用户是管理员，也不能跳过确认；提交后明确告诉用户尚未执行，需到审批中心确认。
               一次请求的所有修改合并为一个清单，不拆成单项绕过批量确认。缺少目标或参数就澄清。
               未入库车辆草稿的补充仍判 INTAKE。
            8. 已有查询记录已经能回答问题，输出 FINISH，不要再重复查同一个东西。
               记录不够才发下一条查询，一次只发一条。
            9. 复杂问题先拆成需要验证的子问题，再逐项查询；每轮检查已完成和缺失的证据。
               可以用聚合、联表、子查询一次取得多个指标，差值、比例等计算优先在 SQL 中完成。
               工具失败时根据错误调整参数或查询方法，不要原样重复失败调用。
               只有全部子问题都有依据才 FINISH；预算不足时明确哪些部分尚未完成。
               总共最多 %d 轮，超过就按已有结果如实回答。
            """;

    private static final String ANSWER_PROMPT = """
            你是二手车门店助手。系统刚执行了一次或多次只读查询，下面是查到的真实结果。

            【硬性规则，违反即失败】
            1. 只能用查询结果里的数字，结果里没有的数字一个都不许写。
            2. 执行的 SQL 里带着问题对应的条件时，结果就是答案，照实说，
               不要因为字段名叫 cnt 就当成「全库总数」。只有当结果明显不是按该条件算出来的
               （例如问超过一个月，却只给了没有任何条件的总台数）才说「这个我没查到，不敢瞎说」，
               不许拿总数凑，也不许推测、估算、编数。
            3. 所有查询结果都是 0 行 → 回答「没有查到符合条件的数据」，不许编数字。
            4. 结果里缺少需要的字段 → 回答「这个我算不出来」，不许自己补。
            5. 不确定就说不知道。禁止任何形式的猜测、类比、换算成别的指标。
            6. 允许基于结果做归纳：结果里有 4 台车、其中 1 台状态是已清库，
               就可以说「1 台已清库、3 台已收车」；小数也可按口语四舍五入。
               这属于照着结果说话，不是编数据。禁止的只是结果里完全没有依据的数字。

            表达要求：简单问题简短回答；复杂问题按结论、依据、建议和待解决事项分段回答，不限制为一句话。
            明确区分数据事实与业务建议；查询失败或证据不足要说明，不能把部分结果说成全部完成。
            金额、台数一律用阿拉伯数字，
            例如写「63857.14 元」「4 台」，不许写「六万三千八百五十七点一四元」「四台」；
            不许出现英文字段名和工具名；
            状态名称（如「已收车」「在售」「已清库」）原样照抄，不许自己翻译或改写；
            「库存」只说「库里」「在库」，不许说成「在售库存」，在售是单独一个状态。
            只输出 JSON：{"reply":"..."}，不要解释文字，不要 markdown 围栏。
            """;

    /** 流式版本：输出纯文本，否则前端会看到 {"reply":"均 这样的 JSON 碎片。 */
    private static final String ANSWER_PROMPT_PLAIN = """
            你是二手车门店助手。系统刚执行了一次或多次只读查询，下面是查到的真实结果。

            【硬性规则，违反即失败】
            1. 只能用查询结果里的数字，结果里没有的数字一个都不许写。
            2. 执行的 SQL 里带着问题对应的条件时，结果就是答案，照实说，不许拿总数凑。
            3. 所有查询结果都是 0 行 → 回答「没有查到符合条件的数据」。
            4. 结果里缺少需要的字段 → 回答「这个我算不出来」。
            5. 不确定就说不知道。禁止猜测、估算、换算成别的指标。
            6. 允许基于结果做归纳：4 台车里 1 台已清库，可以说「1 台已清库、3 台已收车」。

            直接输出中文，不要 JSON。简单问题简短回答，复杂问题按结论、依据、建议和待解决事项分段回答。
            明确区分事实与建议，说明查询失败和未完成部分，不要声称部分结果覆盖全部问题。
            金额、台数用阿拉伯数字；状态名称原样照抄；不许出现英文字段名和工具名；
            「库存」只说「库里」「在库」，不许说成「在售库存」。
            """;

    private static final String SQL_RETRY_PROMPT = """
            你是 MySQL 只读查询修复器。上面那条 SQL 执行失败了，请按错误信息和表结构改写。
            只输出一个 JSON：{"sql":"改写后的 SELECT"}，不要解释，不要 markdown 围栏。
            仍然只准单条 SELECT、只准白名单表、列名显式写、禁止 SELECT * 和任何写操作。
            如果确实无法改写，输出 {"sql":""}。
            """;

    /** 一次工具执行的记录。多轮时按顺序累积，既进路由 prompt 也进润色 prompt。 */
    private record Step(String tool, Map<String, Object> args, String sql,
                        Map<String, Object> result, String error) {
        boolean ok() {
            return error == null;
        }
    }

    /** 路由没拿到可用结果时落的工具名，前端会翻成「识别失败」并标红。 */
    private static final String ROUTE_FAILED = "route_failed";

    /** 落库时间统一按门店时区，跟项目其它表口径一致，不依赖 JVM 默认时区。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 结果 JSON 落库上限：一条 SQL 最多返回 200 行，留 8000 字足够看清结构。 */
    private static final int RESULT_JSON_MAX = 8000;

    private final SiliconFlowClient client;
    private final AiProperties props;
    private final ToolRegistry registry;
    private final ReadonlySqlTool sqlTool;
    private final ObjectMapper objectMapper;
    private final AnswerNumberGuard numberGuard;
    private final AgentTraceRecorder traceRecorder;

    public AgentQueryService(SiliconFlowClient client, AiProperties props,
                             ToolRegistry registry, ReadonlySqlTool sqlTool,
                             ObjectMapper objectMapper, AnswerNumberGuard numberGuard,
                             AgentTraceRecorder traceRecorder) {
        this.client = client;
        this.props = props;
        this.registry = registry;
        this.sqlTool = sqlTool;
        this.objectMapper = objectMapper;
        this.numberGuard = numberGuard;
        this.traceRecorder = traceRecorder;
    }

    /**
     * 尝试回答一个只读问题（非流式）。
     *
     * @return 查询类问题返回答案；收车录入类返回 null，由调用方走原抽取流程
     */
    public String tryAnswer(String userInput, List<IntakeExtractor.HistoryMessage> history) {
        return tryAnswer(null, userInput, history, null);
    }

    /**
     * 带会话 ID 的版本：每轮工具执行完都会往 ai_agent_trace 落一条轨迹。
     * conversationId 为 null 时不落库（单测、脚本调用这类没有会话的场景）。
     */
    public String tryAnswer(Long conversationId, String userInput,
                            List<IntakeExtractor.HistoryMessage> history) {
        return tryAnswer(conversationId, userInput, history, null);
    }

    /**
     * 带流式回调的版本：只有最后的润色阶段会逐段推给前端。
     * onDelta 为 null 时行为与同步版本一致。
     */
    public String tryAnswer(Long conversationId, String userInput,
                            List<IntakeExtractor.HistoryMessage> history,
                            Consumer<String> onDelta) {
        if (!StringUtils.hasText(userInput)) {
            return null;
        }
        // 没配 Key 的 mock 联调环境：不调模型，关键词命中固定工具；
        // 长尾问题没有模型就写不出 SQL，如实说明，不编答案。
        if (!props.hasApiKey()) {
            return keywordAnswer(conversationId, userInput);
        }

        List<Step> trace = new ArrayList<>();
        int maxRounds = props.maxToolRoundsOrDefault();

        for (int round = 0; round < maxRounds; round++) {
            ToolChoice choice = safeRoute(conversationId, userInput, history, trace);
            if (choice == null) {
                // 路由不出结果：已经查过就按已有结果回答，没查过就交给收车流程
                return trace.isEmpty() ? null : finish(userInput, trace, onDelta);
            }
            if ("CHAT".equals(choice.intent())) {
                return StringUtils.hasText(choice.reply()) ? choice.reply() : null;
            }
            if (!"QUERY".equals(choice.intent())) {
                return trace.isEmpty() ? null : finish(userInput, trace, onDelta);
            }
            // 模型判断信息够了，或给了空工具名
            if ("FINISH".equalsIgnoreCase(choice.tool()) || !StringUtils.hasText(choice.tool())) {
                return trace.isEmpty() ? null : finish(userInput, trace, onDelta);
            }

            Step step = execute(choice, userInput, conversationId, trace.size() + 1);
            trace.add(step);
            if (step.ok() && "propose_business_batch".equals(choice.tool())) {
                String reply = "已生成待确认操作清单（申请编号 " + step.result().get("approvalId")
                        + "），共 " + step.result().get("itemCount") + " 项。请管理员在审批中心查看详情并确认；业务数据尚未修改。";
                if (onDelta != null) onDelta.accept(reply);
                return reply;
            }

            // 所有工具都返回规划循环，由模型检查是否覆盖了全部子问题。

        }

        if (trace.isEmpty()) {
            return UNKNOWN;
        }
        log.info("Agent 工具调用达到上限 {} 轮，按已有结果收口", maxRounds);
        return finish(userInput, trace, onDelta);
    }

    /** 收口统一走这里：先记一条可观测日志，再润色。多轮到底跑了几轮，看这行。 */
    private String finish(String userInput, List<Step> trace, Consumer<String> onDelta) {
        log.info("Agent 收口，{} 轮工具调用 {}",
                trace.size(), trace.stream().map(Step::tool).toList());
        return doPolish(userInput, trace, onDelta);
    }

    /**
     * 路由失败也要留痕。
     *
     * 实测遇到过：同一句话问三次，两次正常命中工具、一次模型返回不合规，
     * 那次只回了一句「没有查到能用的信息」，轨迹表里一条记录都没有，
     * 事后完全分不清「没路由」还是「路由了但没命中」还是「工具挂了」。
     * 所以路由拿不到结果时也落一条 ok=0 的轨迹，tool 固定叫 route_failed。
     */
    private ToolChoice safeRoute(Long conversationId, String userInput,
                                 List<IntakeExtractor.HistoryMessage> history,
                                 List<Step> trace) {
        int roundNo = trace.size() + 1;
        long begin = System.nanoTime();
        try {
            ToolChoice choice = route(userInput, history, trace);
            if (choice == null) {
                saveTrace(conversationId, roundNo, userInput,
                        new Step(ROUTE_FAILED, Map.of(), null, null, "模型没有返回可解析的路由结果"),
                        elapsedMs(begin));
            }
            return choice;
        } catch (Exception e) {
            // 路由失败不能打断收车流程
            log.warn("意图路由失败，按已有结果收口: {}", e.getMessage());
            saveTrace(conversationId, roundNo, userInput,
                    new Step(ROUTE_FAILED, Map.of(), null, null, "路由异常：" + e.getMessage()),
                    elapsedMs(begin));
            return null;
        }
    }

    // ==================== 第 1 轮：意图路由 ====================

    private ToolChoice route(String userInput, List<IntakeExtractor.HistoryMessage> history,
                             List<Step> trace) {
        List<Map<String, String>> textMessages = routerMessages(userInput, history, trace);

        if (props.functionCalling()) {
            try {
                List<Map<String, Object>> objectMessages = new ArrayList<>();
                for (Map<String, String> m : textMessages) {
                    objectMessages.add(new LinkedHashMap<>(m));
                }
                SiliconFlowClient.Message message = client.chat(objectMessages, toolDefinitions());
                if (message.hasToolCalls()) {
                    SiliconFlowClient.ToolCall call = message.toolCalls().get(0);
                    // 模型一次可能返回多个 tool_calls（并行查询），这里只执行第一个，
                    // 剩下的下一轮它会重新发——多轮循环本来就允许它分步查。
                    log.info("tools 协议命中: tool={} args={}", call.name(), call.arguments());
                    return new ToolChoice("QUERY", call.name(), parseArgs(call.arguments()), null);
                }
                if (StringUtils.hasText(message.content())) {
                    return parseChoice(message.content(), trace);
                }
                return null;
            } catch (Exception e) {
                // 上游不支持 tools、模型返回 400、解析失败都走这里，回落自定义 JSON 协议
                log.warn("tools 协议不可用，回落自定义 JSON 协议: {}", e.getMessage());
            }
        }
        return routeByJson(textMessages, trace);
    }

    private List<Map<String, String>> routerMessages(String userInput,
                                                     List<IntakeExtractor.HistoryMessage> history,
                                                     List<Step> trace) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(message("system", String.format(ROUTER_PROMPT,
                String.join("\n", registry.descriptions()),
                sqlTool.description(),
                SchemaCatalog.SCHEMA_TEXT,
                traceText(trace),
                props.maxToolRoundsOrDefault())));
        for (IntakeExtractor.HistoryMessage h : history) {
            messages.add(message(h.openAiRole(), h.content()));
        }
        messages.add(message("user", userInput));
        return messages;
    }

    private ToolChoice routeByJson(List<Map<String, String>> messages, List<Step> trace) {
        JsonNode root = readJson(client.chat(messages));
        if (root == null) {
            return null;
        }
        return parseChoice(root.toString(), trace);
    }

    /**
     * 把模型给的一段内容解析成路由结果。
     *
     * tools 协议下模型不调工具时会直接说话（自然语言），不是 JSON；
     * 这种情况如果已经有查询记录，就当作 FINISH 去润色，没记录则交给收车流程。
     */
    private ToolChoice parseChoice(String content, List<Step> trace) {
        JsonNode root = readJson(content);
        if (root == null) {
            return trace.isEmpty() ? null : new ToolChoice("FINISH", "", Map.of(), null);
        }
        String intent = root.path("intent").asText("").toUpperCase(Locale.ROOT);
        if (!"QUERY".equals(intent) && !"CHAT".equals(intent) && !"FINISH".equals(intent)) {
            return null;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        JsonNode argsNode = root.path("args");
        if (argsNode.isObject()) {
            argsNode.fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode()) {
                    args.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        return new ToolChoice(intent, root.path("tool").asText(""), args,
                root.path("reply").asText(null));
    }

    /** tools 协议里的 arguments 是 JSON 字符串，只收标量，嵌套对象直接丢。 */
    private Map<String, Object> parseArgs(String raw) {
        Map<String, Object> args = new LinkedHashMap<>();
        JsonNode node = readJson(raw);
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode()) {
                    args.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        return args;
    }

    private List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>(registry.definitions());
        Map<String, Object> sqlFunction = new LinkedHashMap<>();
        sqlFunction.put("name", ReadonlySqlTool.TOOL_NAME);
        sqlFunction.put("description", sqlTool.description());
        sqlFunction.put("parameters", Map.of(
                "type", "object",
                "properties", Map.of("sql", Map.of(
                        "type", "string",
                        "description", "一条完整的 MySQL SELECT 语句，列名显式写，不要分号")),
                "required", List.of("sql")));
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "function");
        def.put("function", sqlFunction);
        defs.add(def);
        return defs;
    }

    // ==================== 工具执行 ====================

    private Step execute(ToolChoice choice, String userInput, Long conversationId, int roundNo) {
        Map<String, Object> args = choice.args() == null ? Map.of() : choice.args();
        long begin = System.nanoTime();
        Step step;
        try {
            if (ReadonlySqlTool.TOOL_NAME.equals(choice.tool())) {
                String sql = String.valueOf(args.getOrDefault("sql", ""));
                // runSqlWithRetry 返回的是「真正执行成功的那条」：改写过的 SQL 也要留下真实语句
                SqlRun run = runSqlWithRetry(userInput, sql);
                step = new Step(choice.tool(), args, run.sql(), run.result(), null);
            } else {
                step = new Step(choice.tool(), args, null, registry.execute(choice.tool(), args), null);
            }
        } catch (BizException e) {
            // 单轮失败不立刻判死：把错误写进 trace，下一轮模型能看到并改写
            log.warn("工具 {} 执行失败: {}", choice.tool(), e.getMessage());
            step = new Step(choice.tool(), args, null, null, e.getMessage());
        }
        saveTrace(conversationId, roundNo, userInput, step, elapsedMs(begin));
        return step;
    }

    /** SQL 工具的一次执行结果，带「最终真正跑的那条 SQL」。 */
    private record SqlRun(String sql, Map<String, Object> result) {
    }

    /** SQL 执行失败时把错误反馈给模型改写一次；仍失败就如实报错，不猜结果。 */
    private SqlRun runSqlWithRetry(String userInput, String sql) {
        try {
            return new SqlRun(sql, sqlTool.execute(sql));
        } catch (BizException first) {
            log.warn("只读 SQL 首次执行失败，尝试让模型改写: {}", first.getMessage());
            String fixed = rewriteSql(userInput, sql, first.getMessage());
            if (!StringUtils.hasText(fixed)) {
                throw first;
            }
            return new SqlRun(fixed, sqlTool.execute(fixed));
        }
    }

    private String rewriteSql(String userInput, String badSql, String error) {
        List<Map<String, String>> messages = List.of(
                message("system", SQL_RETRY_PROMPT),
                message("user", "老板问：" + userInput
                        + "\n失败的 SQL：" + badSql
                        + "\n错误：" + error
                        + "\n表结构：" + SchemaCatalog.SCHEMA_TEXT));
        try {
            JsonNode root = readJson(client.chat(messages));
            return root == null ? null : root.path("sql").asText(null);
        } catch (Exception e) {
            log.warn("SQL 改写失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 第 2 轮：结果回灌 ====================

    private String doPolish(String userInput, List<Step> trace, Consumer<String> onDelta) {
        // 结果全为空时 Java 直接给结论，不再交给模型：
        // 既避免把「0 条」含糊成「没查到」，也避免在空结果上编数字。
        String empty = emptyResultAnswer(trace);
        if (empty != null) {
            if (onDelta != null) {
                onDelta.accept(empty);
            }
            return empty;
        }

        boolean streaming = onDelta != null;
        String prompt = streaming ? ANSWER_PROMPT_PLAIN : ANSWER_PROMPT;
        List<Map<String, String>> textMessages = List.of(
                message("system", prompt),
                message("user", "老板问：" + userInput + "\n执行记录：\n" + traceText(trace)));

        String answer = null;
        try {
            if (streaming) {
                List<Map<String, Object>> objectMessages = new ArrayList<>();
                for (Map<String, String> m : textMessages) {
                    objectMessages.add(new LinkedHashMap<>(m));
                }
                String full = client.chatStream(objectMessages, onDelta);
                answer = StringUtils.hasText(full) ? full.trim() : null;
            } else {
                JsonNode root = readJson(client.chat(textMessages));
                if (root != null && StringUtils.hasText(root.path("reply").asText())) {
                    answer = root.path("reply").asText();
                }
            }
        } catch (Exception e) {
            log.warn("答案润色失败，改用模板兜底: {}", e.getMessage());
        }
        if (answer == null) {
            answer = template(trace);
            if (onDelta != null) {
                onDelta.accept(answer);
            }
        }
        // 流式时前端已经逐段显示了，这里返回的是「最终认定值」：
        // 上层用它落库，前端收到 guard 事件后拿它替换气泡内容。
        return numberGuard.guard(answer, userInput, resultsOf(trace));
    }

    private static List<Map<String, Object>> resultsOf(List<Step> trace) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Step step : trace) {
            if (step.result() != null) {
                results.add(step.result());
            }
        }
        return results;
    }

    /** 所有成功查询都为空时统一口径，不经模型。不同工具的行数字段名不一样，这里都认。 */
    private static String emptyResultAnswer(List<Step> trace) {
        boolean anyOk = false;
        for (Step step : trace) {
            if (!step.ok() || step.result() == null) {
                return null;
            }
            anyOk = true;
            if (!isEmptyResult(step.result())) {
                return null;
            }
        }
        return anyOk ? "没有查到符合条件的数据。" : null;
    }

    private static boolean isEmptyResult(Map<String, Object> result) {
        for (String key : List.of("rowCount", "count", "total", "pending", "open")) {
            Object v = result.get(key);
            if (v instanceof Number n) {
                return n.longValue() == 0;
            }
        }
        return false;
    }

    // ==================== mock 降级 ====================

    private String keywordAnswer(Long conversationId, String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        String tool;
        Map<String, Object> args = new LinkedHashMap<>();
        if (q.contains("审批") || q.contains("申请单")) {
            tool = "count_pending_approvals";
        } else if (q.contains("售后") || q.contains("结案")) {
            tool = "count_open_after_sales";
        } else if (q.contains("各状态") || q.contains("分布") || q.contains("分别")) {
            tool = "vehicle_status_summary";
        } else if (q.contains("库存号") || lower.contains("uc") || q.contains("查一下") || q.contains("找一下")) {
            tool = "find_vehicle";
            if (q.contains("UC")) {
                int i = q.indexOf("UC");
                args.put("stockNo", q.substring(i, Math.min(i + 15, q.length())).replaceAll("[^A-Za-z0-9]", ""));
            }
        } else if (q.contains("多少台") || q.contains("几台") || q.contains("多少辆")
                || q.contains("几辆") || q.contains("库存") || q.contains("多少车")) {
            // 带额外条件时不能拿总数凑：mock 模式没有模型写 SQL，如实说不知道
            if (q.contains("超过") || q.contains("大于") || q.contains("均价") || q.contains("平均")
                    || q.contains("最多") || q.contains("排名")) {
                return UNKNOWN + "（本地联调模式不支持自由查询，配置 AI 密钥后可用）";
            }
            tool = "count_vehicles";
            if (q.contains("在售")) args.put("status", "ON_SALE");
            else if (q.contains("已成交") || q.contains("成交")) args.put("status", "SOLD");
            else if (q.contains("清库")) args.put("status", "CLEARED");
        } else {
            return null;
        }
        long begin = System.nanoTime();
        try {
            Step step = new Step(tool, args, null, registry.execute(tool, args), null);
            saveTrace(conversationId, 1, q, step, elapsedMs(begin));
            return template(List.of(step));
        } catch (BizException e) {
            saveTrace(conversationId, 1, q, new Step(tool, args, null, null, e.getMessage()), elapsedMs(begin));
            return UNKNOWN;
        }
    }

    /** 模板兜底：模型挂了也要把查到的数字说清楚；通用 SQL 结果只报行数，不编内容。 */
    private static String template(List<Step> trace) {
        Step last = null;
        for (int i = trace.size() - 1; i >= 0; i--) {
            if (trace.get(i).result() != null) {
                last = trace.get(i);
                break;
            }
        }
        if (last == null) {
            return trace.isEmpty() ? UNKNOWN : "查询没有成功：" + trace.get(trace.size() - 1).error();
        }
        String tool = last.tool();
        Map<String, Object> r = last.result();
        return switch (tool) {
            case "count_vehicles" -> "IN_STOCK".equals(String.valueOf(r.get("status")))
                    ? "库里现在还有 " + r.get("total") + " 台未清库的车（另有 "
                      + r.get("clearedExcluded") + " 台已清库，不计入库存）。"
                    : "状态为 " + r.get("status") + " 的车有 " + r.get("total") + " 台。";
            case "vehicle_status_summary" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> s = (Map<String, Object>) r.getOrDefault("summary", Map.of());
                yield "各状态车辆：" + s.entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue() + " 台")
                        .reduce((a, b) -> a + "，" + b).orElse("暂无数据") + "。";
            }
            case "find_vehicle" -> "查到 " + r.get("count") + " 台：" + r.get("vehicles");
            case "count_pending_approvals" -> "还有 " + r.get("pending") + " 条申请等着审批。";
            case "count_open_after_sales" -> "还有 " + r.get("open") + " 条售后单没结案，这些车暂时不能清库。";
            case ReadonlySqlTool.TOOL_NAME -> "查到 " + r.get("rowCount") + " 条记录。";
            default -> UNKNOWN;
        };
    }

    // ==================== 执行轨迹落库 ====================

    private static int elapsedMs(long beginNanos) {
        return (int) ((System.nanoTime() - beginNanos) / 1_000_000);
    }

    /**
     * 落一条执行轨迹。走到 {@link AgentTraceRecorder#record} 之前在独立事务里写入，
     * 外层事务回滚不会把轨迹带进垃圾桶；整段包 try 里，
     * 表锁了、字段超长了、任何异常都只打 warn，不能让老板拿不到答案。
     */
    private void saveTrace(Long conversationId, int roundNo, String question, Step step, int elapsedMs) {
        if (conversationId == null) {
            return;
        }
        try {
            traceRecorder.record(new AiAgentTrace()
                    .setConversationId(conversationId)
                    .setRoundNo(roundNo)
                    .setQuestion(abbreviate(question, 500))
                    .setTool(abbreviate(step.tool(), 64))
                    .setArgsJson(abbreviate(toJson(step.args()), 1000))
                    .setSqlText(step.sql())
                    .setResultJson(abbreviate(
                            step.result() == null ? null : toJson(step.result()), RESULT_JSON_MAX))
                    .setRowCount(rowCountOf(step.result()))
                    .setElapsedMs(elapsedMs)
                    .setOk(step.ok() ? 1 : 0)
                    .setErrorMsg(abbreviate(step.error(), 500))
                    .setCreatedAt(LocalDateTime.now(ZONE)));
        } catch (Exception e) {
            log.warn("Agent 轨迹落库失败（不影响回答）: {}", e.getMessage());
        }
    }

    /** SQL 工具返回真实结果行数；固定工具返回的是它自己的统计值（台数/条数），这里都认。 */
    private static Integer rowCountOf(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        for (String key : List.of("rowCount", "count", "total", "pending", "open")) {
            Object v = result.get(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
        }
        return null;
    }

    /** 超长字段截断，避免 VARCHAR(500)/MEDIUMTEXT 写入报错。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 3)) + "...";
    }

    // ==================== 内部 ====================

    /** 把执行记录拼成一段给模型看的文本，单条结果截断，防止 prompt 被 200 行数据撑爆。 */
    private String traceText(List<Step> trace) {
        if (trace == null || trace.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Step step : trace) {
            sb.append(index++).append(") 工具 ").append(step.tool()).append('\n');
            if (StringUtils.hasText(step.sql())) {
                sb.append("   SQL: ").append(step.sql()).append('\n');
            }
            if (step.error() != null) {
                sb.append("   失败: ").append(step.error()).append('\n');
            } else {
                String json = toJson(step.result());
                sb.append("   结果: ").append(json.length() > RESULT_JSON_MAX ? json.substring(0, RESULT_JSON_MAX) + "\n[结果已截断，请用聚合或缩小范围补查，不能视为完整结果]" : json).append('\n');
            }
        }
        return sb.toString();
    }

    private JsonNode readJson(String content) {
        if (!StringUtils.hasText(content)) {
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
            log.warn("模型返回内容不是合法 JSON: {}", text.length() > 200 ? text.substring(0, 200) : text);
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
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

    private record ToolChoice(String intent, String tool, Map<String, Object> args, String reply) {
    }
}
