package com.usedcar.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.usedcar.agent.dto.CardPatchRequest;
import com.usedcar.agent.dto.ChatRequest;
import com.usedcar.agent.dto.ChatResponse;
import com.usedcar.agent.dto.ConfirmResult;
import com.usedcar.agent.dto.ConversationDetailVO;
import com.usedcar.agent.dto.ConversationVO;
import com.usedcar.agent.dto.IntakeCardDTO;
import com.usedcar.agent.dto.TraceVO;
import com.usedcar.agent.service.AiIntakeService;
import com.usedcar.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * AI 收车接口。
 *
 * 和 VehicleController 一样，这一层只接参数、调 Service、包响应，不写业务判断。
 * 全部挂在 /api/** 下，由 SaTokenConfig 统一要求登录；
 * 会话归属的二次校验在 AiIntakeService.requireOwned，防止改 URL 翻别人的聊天记录。
 */
@Tag(name = "AI 收车")
@RestController
@RequestMapping("/api/ai/conversations")
public class AiIntakeController {

    private final AiIntakeService service;
    private final TaskExecutor taskExecutor;

    public AiIntakeController(AiIntakeService service,
                              @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.service = service;
        this.taskExecutor = taskExecutor;
    }

    @Operation(summary = "当前登录人的会话列表")
    @GetMapping
    public ApiResult<List<ConversationVO>> list() {
        return ApiResult.ok(service.listConversations());
    }

    @Operation(summary = "新建收车会话")
    @PostMapping
    public ApiResult<ConversationVO> create() {
        return ApiResult.ok(service.createConversation());
    }

    @Operation(summary = "会话详情：全部消息 + 每轮卡片快照 + 当前卡片")
    @GetMapping("/{id}")
    public ApiResult<ConversationDetailVO> detail(@PathVariable Long id) {
        return ApiResult.ok(service.detail(id));
    }

    @Operation(summary = "Agent 执行轨迹：本会话每次工具调用的工具名、参数、SQL、行数、耗时")
    @GetMapping("/{id}/traces")
    public ApiResult<List<TraceVO>> traces(@PathVariable Long id) {
        // 登录人必须在当前请求线程取，交给 Service 做归属校验；
        // 不在 Service 里再读一次是因为两者都可能跑在 SSE 独立线程上。
        return ApiResult.ok(service.listTraces(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "发一句自然语言，返回 AI 回复与最新卡片（不写业务表）")
    @PostMapping("/{id}/messages")
    public ApiResult<ChatResponse> chat(@PathVariable Long id,
                                        @Valid @RequestBody ChatRequest request) {
        return ApiResult.ok(service.chat(id, request.content()));
    }

    /**
     * 流式对话。事件三种：
     *   delta —— {"text":"增量片段"}，查询命中时边生成边推
     *   done  —— 完整的 ChatResponse（含最终 reply 和卡片），前端据此替换气泡并刷新详情
     *   error —— {"message":"..."}，前端应回退到非流式接口重试
     *
     * 为什么在 Controller 里先取 viewerId：流式任务在 applicationTaskExecutor 线程执行，
     * Sa-Token 的登录态是 ThreadLocal，换线程就取不到，会直接抛 NotLoginException。
     * 同理，这里不能用 service 内部自调用——同类自调用不走代理，@Transactional 会失效。
     */
    @Operation(summary = "流式发送（SSE）：delta 增量 + done 最终结果")
    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable Long id, @Valid @RequestBody ChatRequest request) {
        Long viewerId = StpUtil.getLoginIdAsLong();
        SseEmitter emitter = new SseEmitter(180_000L);

        taskExecutor.execute(() -> {
            try {
                ChatResponse response = service.chat(id, request.content(), viewerId,
                        delta -> write(emitter, "delta", Map.of("text", delta)));
                write(emitter, "done", response);
            } catch (Throwable e) {
                write(emitter, "error", Map.of("message", messageOf(e)));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 客户端断开后继续写会抛异常，吞掉即可：主流程会自然结束，数据已经落库。 */
    private void write(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 连接已关闭，后续事件没有接收方
        }
    }

    private static String messageOf(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? "服务端处理失败" : m;
    }

    @Operation(summary = "直接改卡片字段 / 跳过建议字段（不写业务表）")
    @PatchMapping("/{id}/card")
    public ApiResult<IntakeCardDTO> patchCard(@PathVariable Long id,
                                              @RequestBody CardPatchRequest request) {
        return ApiResult.ok(service.patchCard(id, request));
    }

    @Operation(summary = "老板确认后才调业务工具写库")
    @PostMapping("/{id}/card/confirm")
    public ApiResult<ConfirmResult> confirm(@PathVariable Long id) {
        return ApiResult.ok(service.confirm(id));
    }

    @Operation(summary = "放弃当前卡片")
    @PostMapping("/{id}/card/discard")
    public ApiResult<IntakeCardDTO> discard(@PathVariable Long id) {
        return ApiResult.ok(service.discard(id));
    }
}
