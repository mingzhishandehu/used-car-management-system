package com.usedcar.agent.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.agent.config.AiProperties;
import com.usedcar.approval.service.ApprovalGate;
import com.usedcar.agent.domain.AiAgentTrace;
import com.usedcar.agent.domain.AiConversation;
import com.usedcar.agent.domain.AiMessage;
import com.usedcar.agent.dto.CardPatchRequest;
import com.usedcar.agent.dto.ChatResponse;
import com.usedcar.agent.dto.ConfirmResult;
import com.usedcar.agent.dto.ConversationDetailVO;
import com.usedcar.agent.dto.ConversationVO;
import com.usedcar.agent.dto.FieldSpecDTO;
import com.usedcar.agent.dto.IntakeCardDTO;
import com.usedcar.agent.dto.MessageVO;
import com.usedcar.agent.dto.TraceVO;
import com.usedcar.agent.mapper.AiAgentTraceMapper;
import com.usedcar.agent.mapper.AiConversationMapper;
import com.usedcar.agent.mapper.AiMessageMapper;
import com.usedcar.agent.tool.CreateVehicleTool;
import com.usedcar.common.BizException;
import com.usedcar.vehicle.dto.VehicleCreateDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * AI 收车编排层。整条链路只有这里知道「先做什么、后做什么」。
 *
 * 三条硬边界：
 *   1. 模型输出只是不可信输入，统一过 IntakeFieldSpec.sanitize 白名单 + 类型归一化。
 *   2. 本类只注入 ai_conversation / ai_message 两张表的 Mapper，业务表一个都不认识。
 *   3. 写业务数据只有 confirm() 一处，且必须先 CAS 成功、再由 CreateVehicleTool 执行。
 *
 * 会话状态：NONE → DRAFT → CONFIRMED（或 DISCARDED）。
 * CONFIRMED 之后仍可继续聊天查记录，但不再产出新卡片，已确认的车不会被 AI 改动。
 */
@Service
public class AiIntakeService {

    private static final Logger log = LoggerFactory.getLogger(AiIntakeService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DEFAULT_TITLE = "新的收车会话";

    private static final List<FieldSpecDTO> SPEC = IntakeFieldSpec.ALL.stream()
            .map(f -> new FieldSpecDTO(f.key(), f.label(), f.group().name(), f.type().name(), f.hint()))
            .toList();

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiAgentTraceMapper traceMapper;
    private final IntakeExtractor extractor;
    private final CreateVehicleTool createVehicleTool;
    private final ObjectMapper objectMapper;
    private final AiProperties props;
    private final ApprovalGate approvalGate;
    private final AgentQueryService agentQueryService;
    private final ConversationSummaryService summaryService;

    public AiIntakeService(AiConversationMapper conversationMapper,
                           AiMessageMapper messageMapper,
                           AiAgentTraceMapper traceMapper,
                           IntakeExtractor extractor,
                           CreateVehicleTool createVehicleTool,
                           ObjectMapper objectMapper,
                           AiProperties props,
                           ApprovalGate approvalGate,
                           AgentQueryService agentQueryService,
                           ConversationSummaryService summaryService) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.traceMapper = traceMapper;
        this.extractor = extractor;
        this.createVehicleTool = createVehicleTool;
        this.objectMapper = objectMapper;
        this.props = props;
        this.approvalGate = approvalGate;
        this.agentQueryService = agentQueryService;
        this.summaryService = summaryService;
    }

    // ==================== 会话 ====================

    public List<ConversationVO> listConversations() {
        List<AiConversation> rows = conversationMapper.selectList(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getOwnerUserId, StpUtil.getLoginIdAsLong())
                        .orderByDesc(AiConversation::getUpdatedAt)
                        .orderByDesc(AiConversation::getId));
        return rows.stream().map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public ConversationVO createConversation() {
        AiConversation conv = new AiConversation()
                .setTitle(DEFAULT_TITLE)
                .setOwnerUserId(StpUtil.getLoginIdAsLong())
                .setDraftJson("{}")
                .setCardStatus("NONE")
                .setSkipOptional(0);
        conversationMapper.insert(conv);
        // created_at 是数据库默认值，insert 后 Java 对象里仍是 null，回查一次再返回
        return toVO(conversationMapper.selectById(conv.getId()));
    }

    public ConversationDetailVO detail(Long id) {
        AiConversation conv = requireOwned(id);
        List<AiMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, id)
                        .orderByAsc(AiMessage::getId));

        List<MessageVO> vos = messages.stream()
                .map(m -> new MessageVO(m.getId(), m.getRole(), m.getContent(),
                        parseCard(m.getCardJson()), m.getCreatedAt()))
                .toList();

        Map<String, Object> draft = readDraft(conv);
        IntakeCardDTO card = buildCard(conv, draft, stockNoOf(conv));
        return new ConversationDetailVO(toVO(conv), vos, card);
    }

    /**
     * 某个会话的 Agent 执行轨迹，按发生顺序返回。
     *
     * 归属校验走同一个 requireOwned：轨迹里有老板的原话和执行过的 SQL，
     * 跟聊天记录同级，改 URL 也不能看别人的。
     * 上限 200 条：一个会话问上百次已属异常，加上限防止前端一次拉爆。
     */
    public List<TraceVO> listTraces(Long id, Long viewerId) {
        requireOwned(id, viewerId);
        return traceMapper.selectList(new LambdaQueryWrapper<AiAgentTrace>()
                        .eq(AiAgentTrace::getConversationId, id)
                        .orderByAsc(AiAgentTrace::getId)
                        .last("LIMIT 200"))
                .stream()
                .map(t -> new TraceVO(
                        t.getId(),
                        t.getRoundNo(),
                        t.getQuestion(),
                        t.getTool(),
                        t.getArgsJson(),
                        t.getSqlText(),
                        t.getRowCount(),
                        t.getElapsedMs(),
                        Integer.valueOf(1).equals(t.getOk()),
                        t.getErrorMsg(),
                        preview(t.getResultJson()),
                        t.getCreatedAt()))
                .toList();
    }

    /**
     * 轨迹结果摘要：只给前 600 字。
     *
     * 不复用下面的 abbreviate：那个方法是给会话标题用的，传 null 会返回「新的收车会话」，
     * 套到结果 JSON 上会凭空造出一条假数据。
     */
    private static String preview(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String t = json.trim();
        return t.length() <= 600 ? t : t.substring(0, 600) + "…";
    }

    // ==================== 对话 ====================

    /**
     * 老板说一句话 → 抽取 → 合并进草稿 → 回一张卡片。
     * 全程不写业务表，哪怕模型把整辆车的信息都吐出来了，也只落在 draft_json。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatResponse chat(Long id, String content) {
        try {
            return doChat(id, content, StpUtil.getLoginIdAsLong(), null);
        } finally {
            // 事务提交后再异步压缩：一次模型调用要几秒钟，
            // 放在主事务里会让这条数据库连接一直等着，并发几个会话就能把连接池拖空。
            // 回滚时 afterCommit 不触发，正好不该压缩。
            triggerSummaryAfterCommit(id);
        }
    }

    /**
     * 流式版本：查询命中时边生成边把增量交给 onDelta，返回的仍是完整结果。
     *
     * 为什么事务还留在这里：卡片草稿、用户消息、assistant 消息必须原子落库。
     * 流式只影响「答案文字怎么到前端」，不影响数据一致性。
     * 两个 chat 方法各自标 @Transactional，不能互相调用——
     * 同类自调用不走代理，注解会失效。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatResponse chat(Long id, String content, Long viewerId, Consumer<String> onDelta) {
        try {
            return doChat(id, content, viewerId, onDelta);
        } finally {
            triggerSummaryAfterCommit(id);
        }
    }

    private ChatResponse doChat(Long id, String content, Long viewerId, Consumer<String> onDelta) {
        AiConversation conv = requireOwned(id, viewerId);

        // 历史必须在写入本轮 USER 消息之前取，否则模型会在 messages 里看到同一句话两次。
        // 意图路由和收车抽取共用这一份，摘要也在这里拼进去。
        List<IntakeExtractor.HistoryMessage> history = loadHistory(conv);

        // 只读问答优先：问库存、审批、售后这类数据时走工具调用，
        // 不产生卡片、不改草稿、不改卡片状态。所以已确认 / 待审批的会话也能继续问数据。
        // onDelta 只有流式请求才非空，非流式传 null 行为完全不变。
        // 传会话 ID 进门：多轮循环里每执行一个工具都会往 ai_agent_trace 落一条轨迹，
        // 前端「执行轨迹」面板靠它还原「这轮 AI 查了什么、跑的哪条 SQL」。
        String answer = AgentActorContext.withViewer(viewerId,
                () -> agentQueryService.tryAnswer(id, content, history, onDelta));
        if (answer != null) {
            appendMessage(id, "USER", content, null);
            appendMessage(id, "ASSISTANT", answer, null);
            return new ChatResponse(id, answer, buildCard(conv, readDraft(conv), stockNoOf(conv)));
        }

        // CONFIRMED / PENDING 都是终态：卡片已锁定，继续聊天不能把状态改回 DRAFT，
        // 否则员工能在审批途中改草稿、甚至再提交一张重复申请。
        if ("CONFIRMED".equals(conv.getCardStatus()) || "PENDING".equals(conv.getCardStatus())) {
            String reply = "CONFIRMED".equals(conv.getCardStatus())
                    ? "这辆车已经录入系统了，卡片已确认不能改。要改信息请去车辆详情页；要收下一辆车请新建会话。"
                    : "这辆车已提交申请，正在等管理员审批，卡片已锁定。要收下一辆车请新建会话。";
            appendMessage(id, "USER", content, null);
            appendMessage(id, "ASSISTANT", reply, null);
            return new ChatResponse(id, reply, buildCard(conv, readDraft(conv), stockNoOf(conv)));
        }

        Map<String, Object> draft = readDraft(conv);
        LocalDate today = LocalDate.now(ZONE);

        if ("DISCARDED".equals(conv.getCardStatus())) {
            // 放弃过的卡片重新开一张，旧草稿不留，否则老板会看到上次不要的车
            draft.clear();
            conv.setSkipOptional(0);
        }

        IntakeExtractor.ExtractionResult result = extractor.extract(
                new IntakeExtractor.ExtractionContext(draft, history, content, today));

        // 只接受白名单字段，类型不对的直接丢；模型自造的字段在这行被拦下
        Map<String, Object> clean = IntakeFieldSpec.sanitize(result.fields(), today);

        // 空会话 + 一个字段都没抽到 = 这句话根本不是收车描述。
        // 以前这类输入（问隐私、要求删数据、闲聊）会掉进抽取器的「这句我没解析出来」，
        // 显得系统坏了。这里统一给出能力说明，不改草稿、不推进卡片状态。
        // 只在 NONE 生效：会话已有 DRAFT 时，「改成 7 万」「跳过」这类合法补充照常走。
        if ("NONE".equals(conv.getCardStatus()) && clean.isEmpty()) {
            String hint = "这个问题我这边没有查到能用的信息。库存、价格、状态、审批、售后这类数据可以直接问我；"
                    + "要录车的话，说一下品牌、车型、收车价和收车日期。";
            appendMessage(id, "USER", content, null);
            appendMessage(id, "ASSISTANT", hint, null);
            return new ChatResponse(id, hint, buildCard(conv, draft, null));
        }

        draft.putAll(clean);

        // 「今天收的 / 昨天收的」是收车口语里最高频的说法，模型偶尔会漏掉这类相对日期，
        // 实测就出现过：老板说了「今天」，AI 还追问收车日期。
        // 高频且能确定性换算的输入不交给概率：草稿和本轮结果都没给日期时，Java 侧兜底。
        // 老板若觉得不对，可以直接在卡片上改，卡片始终可编辑。
        if (!draft.containsKey("purchaseDate") && !clean.containsKey("purchaseDate")) {
            String relative = content.contains("今天") ? "今天"
                    : content.contains("昨天") ? "昨天" : null;
            if (relative != null) {
                Object date = IntakeFieldSpec.normalize("purchaseDate", relative, today);
                if (date != null) {
                    draft.put("purchaseDate", date);
                }
            }
        }

        appendMessage(id, "USER", content, null);

        conv.setDraftJson(writeJson(draft));
        conv.setCardStatus("DRAFT");
        if (DEFAULT_TITLE.equals(conv.getTitle())) {
            conv.setTitle(abbreviate(content, 20));
        }
        conversationMapper.updateById(conv);

        IntakeCardDTO card = buildCard(conv, draft, null);
        String reply = hasText(result.reply()) ? result.reply() : defaultReply(card);
        appendMessage(id, "ASSISTANT", reply, card);

        log.info("AI 收车会话 {} 本轮抽取字段 {}", id, clean.keySet());
        return new ChatResponse(id, reply, card);
    }

    // ==================== 卡片操作 ====================

    /** 老板直接在卡片里改字段，或点「跳过这些直接确认」。同样只改草稿，不写业务表。 */
    @Transactional(rollbackFor = Exception.class)
    public IntakeCardDTO patchCard(Long id, CardPatchRequest request) {
        AiConversation conv = requireDraft(id);
        Map<String, Object> draft = readDraft(conv);
        LocalDate today = LocalDate.now(ZONE);

        if (request.fields() != null) {
            for (Map.Entry<String, Object> entry : request.fields().entrySet()) {
                String key = entry.getKey();
                IntakeFieldSpec.Field field = IntakeFieldSpec.byKey(key);
                if (field == null) {
                    // 前端被改坏、或有人手工构造请求塞了怪字段：忽略，不报错打断编辑
                    continue;
                }
                if (entry.getValue() == null || String.valueOf(entry.getValue()).isBlank()) {
                    draft.remove(key);
                    continue;
                }
                Object value = IntakeFieldSpec.normalize(key, entry.getValue(), today);
                if (value == null) {
                    throw new BizException(400, field.label() + " 填的值不合法，请检查格式");
                }
                draft.put(key, value);
            }
        }
        if (Boolean.TRUE.equals(request.skipOptional())) {
            conv.setSkipOptional(1);
        }
        conv.setDraftJson(writeJson(draft));
        conversationMapper.updateById(conv);
        return buildCard(conv, draft, null);
    }

    /**
     * 老板点确认，才真正写库。
     *
     * 幂等靠两道：
     *   1. casConfirm 把条件写进 UPDATE 的 WHERE，并发下第二个请求影响行数为 0；
     *   2. ai_conversation.uk_conv_vehicle 唯一键，兜住极端情况下的重复产出。
     * createVehicleTool.execute 与 CAS 在同一个事务里，写车失败会一起回滚，
     * 卡片回到 DRAFT，老板能重试，不会出现「卡片显示已确认但库里没车」。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConfirmResult confirm(Long id) {
        AiConversation conv = requireDraft(id);
        Map<String, Object> draft = readDraft(conv);

        IntakeCardDTO before = buildCard(conv, draft, null);
        if (!before.readyToConfirm()) {
            throw new BizException(400, before.missingRequired().isEmpty()
                    ? "还有建议字段没补齐，可点「跳过这些直接确认」"
                    : "必填项还没补齐，暂时不能录入");
        }

        VehicleCreateDTO payload = toCreateDTO(draft);
        // 哪个账号用 AI 收车，申请单的申请人就是谁。批准后 created_by / owner_user_id
        // 都记成这个账号，不记审批的老板——这是用户第 4 条明确要求的归属规则。
        Long pendingId = approvalGate.submitIfEmployee("AI_INTAKE_CREATE", "AI", null, payload, id);

        if (pendingId != null) {
            // 员工分支：只落申请单，绝不写业务表。CAS 失败时抛异常，
            // 同一事务会把刚插入的申请单一起回滚，不会留下孤儿申请。
            if (conversationMapper.casPending(id) == 0) {
                throw new BizException(400, "该卡片已提交，请勿重复操作");
            }
            conv.setCardStatus("PENDING");
            IntakeCardDTO card = buildCard(conv, draft, null);
            appendMessage(id, "ASSISTANT",
                    "已提交申请（单号 " + pendingId + "），等待管理员审批。这张卡片已锁定，不能再改。", card);
            log.info("AI 收车会话 {} 提交审批申请 {}", id, pendingId);
            return new ConfirmResult(null, null, card, false, pendingId);
        }

        // ADMIN 分支：直接写库，保持原来的确认路径
        int updated = conversationMapper.casConfirm(id);
        if (updated == 0) {
            throw new BizException(400, "该会话已确认，请勿重复提交");
        }

        Long vehicleId = createVehicleTool.execute(payload);
        String stockNo = createVehicleTool.stockNoOf(vehicleId);

        conv.setCardStatus("CONFIRMED");
        conv.setVehicleId(vehicleId);
        AiConversation patch = new AiConversation()
                .setId(id)
                .setVehicleId(vehicleId);
        conversationMapper.updateById(patch);

        IntakeCardDTO card = buildCard(conv, draft, stockNo);
        appendMessage(id, "ASSISTANT",
                "已确认录入，库存编号 " + stockNo + "。这张卡片已锁定，不能再改。", card);

        log.info("AI 收车会话 {} 确认录入 vehicleId={} stockNo={}", id, vehicleId, stockNo);
        return new ConfirmResult(vehicleId, stockNo, card, true, null);
    }

    /**
     * 审批通过后回写会话卡片。由 ApprovalService.approve() 在业务写入成功之后调用，
     * 两边共用同一个事务：回写失败会连车辆一起回滚，不会出现「车入库了但卡片还显示待审批」。
     *
     * 只回写 card_status / vehicle_id / 一条说明消息，不改草稿、不删任何历史消息。
     * 前端靠 detail.card.status === 'CONFIRMED' 把最后一张卡片渲染成灰色只读。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markApproved(Long conversationId, Long vehicleId, String stockNo) {
        AiConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !"PENDING".equals(conv.getCardStatus())) {
            return;
        }
        conversationMapper.updateById(new AiConversation()
                .setId(conversationId)
                .setCardStatus("CONFIRMED")
                .setVehicleId(vehicleId));
        appendMessage(conversationId, "ASSISTANT",
                "申请已通过，车辆已录入，库存编号 " + stockNo + "。这张卡片已锁定，不能再改。", null);
        log.info("AI 收车会话 {} 的申请已通过，卡片回写为 CONFIRMED，vehicleId={}", conversationId, vehicleId);
    }

    @Transactional(rollbackFor = Exception.class)
    public IntakeCardDTO discard(Long id) {
        AiConversation conv = requireDraft(id);
        conv.setCardStatus("DISCARDED");
        conv.setSkipOptional(0);
        conversationMapper.updateById(conv);

        IntakeCardDTO card = buildCard(conv, readDraft(conv), null);
        appendMessage(id, "ASSISTANT", "这张卡片已放弃。继续发消息可以重新录入。", card);
        return card;
    }

    // ==================== 内部 ====================

    private AiConversation requireOwned(Long id) {
        return requireOwned(id, StpUtil.getLoginIdAsLong());
    }

    /**
     * 显式传 viewerId 的重载。
     *
     * SSE 流式在独立线程执行，StpUtil 的登录态存在 ThreadLocal 里，
     * 换个线程就取不到（会抛 NotLoginException）。所以登录人必须在
     * Controller 线程里先取出来，一路传进来，不能在这里再读一次。
     */
    private AiConversation requireOwned(Long id, Long viewerId) {
        AiConversation conv = conversationMapper.selectById(id);
        if (conv == null) {
            throw new BizException(404, "会话不存在");
        }
        if (!Objects.equals(conv.getOwnerUserId(), viewerId)) {
            // 聊天记录里有老板原话，改 URL 翻别人的记录属于越权，必须挡住
            throw new BizException(403, "无权访问该会话");
        }
        return conv;
    }

    private AiConversation requireDraft(Long id) {
        AiConversation conv = requireOwned(id);
        if (!"DRAFT".equals(conv.getCardStatus())) {
            throw new BizException(400, "当前卡片不可操作");
        }
        return conv;
    }

    /**
     * 取进模型的历史：一段摘要（如果有）+ 摘要覆盖点之后最近 N 条原文。
     *
     * 以前只取最近 N 条，超长会话的早期上下文直接丢；
     * 现在超出的部分被 ConversationSummaryService 压成 summary_text，
     * 作为 SYSTEM 消息排在最前面，模型仍然知道「这辆车是什么、改过什么」。
     */
    private List<IntakeExtractor.HistoryMessage> loadHistory(AiConversation conv) {
        int limit = Math.max(1, props.maxHistory());
        long after = conv.getSummarizedMsgId() == null ? 0L : conv.getSummarizedMsgId();

        // 倒序取最近 N 条再反转；limit 来自配置里的 int，不拼用户输入，无注入风险
        List<AiMessage> recent = messageMapper.selectList(
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, conv.getId())
                        .gt(AiMessage::getId, after)
                        .orderByDesc(AiMessage::getId)
                        .last("LIMIT " + limit));
        Collections.reverse(recent);

        List<IntakeExtractor.HistoryMessage> history = new ArrayList<>();
        if (conv.getSummaryText() != null && !conv.getSummaryText().isBlank()) {
            history.add(new IntakeExtractor.HistoryMessage("SYSTEM",
                    "更早的对话摘要（这部分原文已压缩，内容仍然有效）：" + conv.getSummaryText()));
        }
        for (AiMessage m : recent) {
            history.add(new IntakeExtractor.HistoryMessage(m.getRole(), m.getContent()));
        }
        return history;
    }

    /** 事务提交后触发异步压缩；没有事务上下文（理论上不该发生）就不触发。 */
    private void triggerSummaryAfterCommit(Long conversationId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                summaryService.compressAsync(conversationId);
            }
        });
    }

    private void appendMessage(Long conversationId, String role, String content, IntakeCardDTO card) {
        AiMessage row = new AiMessage()
                .setConversationId(conversationId)
                .setRole(role)
                .setContent(content)
                .setCardJson(card == null ? null : writeJson(card));
        messageMapper.insert(row);
    }

    private Map<String, Object> readDraft(AiConversation conv) {
        if (conv.getDraftJson() == null || conv.getDraftJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(conv.getDraftJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return new LinkedHashMap<>(map);
        } catch (Exception e) {
            log.warn("会话 {} 的草稿 JSON 解析失败，按空草稿处理", conv.getId());
            return new LinkedHashMap<>();
        }
    }

    private IntakeCardDTO parseCard(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, IntakeCardDTO.class);
        } catch (Exception e) {
            log.warn("卡片快照解析失败，跳过该快照");
            return null;
        }
    }

    private IntakeCardDTO buildCard(AiConversation conv, Map<String, Object> draft, String stockNo) {
        List<String> missingRequired = IntakeFieldSpec.missing(draft, IntakeFieldSpec.REQUIRED);
        List<String> missingOptional = IntakeFieldSpec.missing(draft, IntakeFieldSpec.OPTIONAL);
        boolean skip = Integer.valueOf(1).equals(conv.getSkipOptional());
        boolean ready = "DRAFT".equals(conv.getCardStatus())
                && missingRequired.isEmpty()
                && (missingOptional.isEmpty() || skip);
        return new IntakeCardDTO(
                conv.getCardStatus(),
                new LinkedHashMap<>(draft),
                SPEC,
                missingRequired,
                missingOptional,
                skip,
                ready,
                conv.getVehicleId(),
                stockNo);
    }

    private String stockNoOf(AiConversation conv) {
        return conv.getVehicleId() == null ? null : createVehicleTool.stockNoOf(conv.getVehicleId());
    }

    private String defaultReply(IntakeCardDTO card) {
        if (!card.missingRequired().isEmpty()) {
            return "还缺必填项：" + labels(card.missingRequired()) + "。麻烦补一下，我接着记。";
        }
        if (!card.missingOptional().isEmpty() && !card.skipOptional()) {
            return "必填齐了。还有建议字段：" + labels(card.missingOptional())
                    + "，可以补，也可以点「跳过这些直接确认」。";
        }
        return "信息齐了。请核对卡片，点「确认录入」之后才会写进系统。";
    }

    private String labels(List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String key : keys) {
            IntakeFieldSpec.Field f = IntakeFieldSpec.byKey(key);
            names.add(f == null ? key : f.label());
        }
        return String.join("、", names);
    }

    /** 草稿 → 收车入库入参。ownerUserId 不填：它是内部归属字段，不出现在老板确认的卡片上。 */
    private VehicleCreateDTO toCreateDTO(Map<String, Object> d) {
        return new VehicleCreateDTO(
                text(d, "vin"),
                text(d, "plateNo"),
                text(d, "brand"),
                text(d, "model"),
                date(d, "firstRegDate"),
                integer(d, "mileage"),
                money(d, "purchasePrice"),
                date(d, "purchaseDate"),
                money(d, "listPrice"),
                null,
                text(d, "remark"));
    }

    private static String text(Map<String, Object> d, String key) {
        Object v = d.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal money(Map<String, Object> d, String key) {
        String v = text(d, key);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(Map<String, Object> d, String key) {
        String v = text(d, key);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer integer(Map<String, Object> d, String key) {
        Object v = d.get(key);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v)).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(500, "会话数据序列化失败");
        }
    }

    private ConversationVO toVO(AiConversation c) {
        return new ConversationVO(c.getId(), c.getTitle(), c.getCardStatus(),
                c.getVehicleId(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return DEFAULT_TITLE;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
