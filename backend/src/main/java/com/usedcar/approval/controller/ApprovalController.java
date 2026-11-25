package com.usedcar.approval.controller;

import com.usedcar.approval.dto.ApprovalActionRequest;
import com.usedcar.approval.dto.ApprovalResubmitRequest;
import com.usedcar.approval.dto.ApprovalVO;
import com.usedcar.approval.service.ApprovalFieldSpec;
import com.usedcar.approval.service.ApprovalService;
import com.usedcar.common.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 车辆审批接口。
 * 待办和全部列表在 ApprovalService 里校验 ADMIN；「我的申请」任何登录账号可读。
 */
@Tag(name = "车辆审批")
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Operation(summary = "申请列表：scope=pending 待办 / mine 我的申请 / all 全部")
    @GetMapping
    public ApiResult<List<ApprovalVO>> list(
            @RequestParam(defaultValue = "mine") String scope,
            @RequestParam(required = false) String status) {
        return ApiResult.ok(approvalService.list(scope, status));
    }

    @Operation(summary = "复制重提表单的字段规格")
    @GetMapping("/spec")
    public ApiResult<Map<String, List<ApprovalFieldSpec.Field>>> spec() {
        return ApiResult.ok(approvalService.spec());
    }

    @Operation(summary = "批准申请（仅管理员），批准后执行业务写入")
    @PostMapping("/{id}/approve")
    public ApiResult<ApprovalVO> approve(@PathVariable Long id,
                                         @RequestBody(required = false) ApprovalActionRequest request) {
        return ApiResult.ok(approvalService.approve(id, request == null ? null : request.remark()));
    }

    @Operation(summary = "驳回申请（仅管理员），必须填原因")
    @PostMapping("/{id}/reject")
    public ApiResult<ApprovalVO> reject(@PathVariable Long id,
                                        @RequestBody ApprovalActionRequest request) {
        return ApiResult.ok(approvalService.reject(id, request == null ? null : request.remark()));
    }

    @Operation(summary = "复制被驳回的申请，改参数后重新提交")
    @PostMapping("/{id}/resubmit")
    public ApiResult<ApprovalVO> resubmit(@PathVariable Long id,
                                          @RequestBody ApprovalResubmitRequest request) {
        return ApiResult.ok(approvalService.resubmit(id, request == null ? null : request.payload()));
    }

    @Operation(summary = "撤回自己待审批的申请")
    @PostMapping("/{id}/cancel")
    public ApiResult<Void> cancel(@PathVariable Long id) {
        approvalService.cancel(id);
        return ApiResult.ok();
    }
}
