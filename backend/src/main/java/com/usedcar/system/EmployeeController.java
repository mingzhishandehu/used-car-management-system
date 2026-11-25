package com.usedcar.system;

import com.usedcar.common.ApiResult;
import com.usedcar.system.dto.EmployeeCreateDTO;
import com.usedcar.system.dto.EmployeeLeaveRequest;
import com.usedcar.system.dto.EmployeePasswordDTO;
import com.usedcar.system.dto.EmployeeUpdateDTO;
import com.usedcar.system.dto.EmployeeVO;
import com.usedcar.system.dto.LeaveResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 员工管理接口。
 *
 * 权限分层：
 *   GET 列表         → 只要登录。车辆列表页要拿它做「录入人/对接人」姓名映射，
 *                      收车表单要拿它做对接人下拉，普通员工也需要读。
 *   其余全部写操作   → EmployeeService.requireAdmin()，只有老板的 ADMIN 账号能过。
 * 不在 Controller 里散着写角色判断，权限判断只有一个入口。
 */
@Tag(name = "员工管理")
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @Operation(summary = "员工列表，支持按登录名/姓名/手机号搜索、按在职状态筛选")
    @GetMapping
    public ApiResult<List<EmployeeVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String employmentStatus) {
        return ApiResult.ok(employeeService.list(keyword, employmentStatus));
    }

    @Operation(summary = "该员工名下待流转的未完成车辆数（仅管理员）")
    @GetMapping("/{id}/pending-handover-count")
    public ApiResult<Long> pendingHandoverCount(@PathVariable Long id) {
        return ApiResult.ok(employeeService.pendingHandoverCount(id));
    }

    @Operation(summary = "办理离职：未完成车辆对接权流转给接手人，全程留痕（仅管理员）")
    @PostMapping("/{id}/leave")
    public ApiResult<LeaveResult> leave(@PathVariable Long id,
                                        @Valid @RequestBody EmployeeLeaveRequest request) {
        return ApiResult.ok(employeeService.leave(id, request.successorId(), request.reason()));
    }

    @Operation(summary = "新增员工（仅管理员）")
    @PostMapping
    public ApiResult<EmployeeVO> create(@Valid @RequestBody EmployeeCreateDTO dto) {
        return ApiResult.ok(employeeService.create(dto));
    }

    @Operation(summary = "修改员工姓名/手机号/角色（仅管理员）")
    @PutMapping("/{id}")
    public ApiResult<EmployeeVO> update(@PathVariable Long id,
                                        @Valid @RequestBody EmployeeUpdateDTO dto) {
        return ApiResult.ok(employeeService.update(id, dto));
    }

    @Operation(summary = "停用或启用员工（仅管理员）")
    @PostMapping("/{id}/status")
    public ApiResult<Void> setStatus(@PathVariable Long id, @RequestParam Integer status) {
        employeeService.setStatus(id, status);
        return ApiResult.ok();
    }

    @Operation(summary = "重置员工密码（仅管理员）")
    @PostMapping("/{id}/password")
    public ApiResult<Void> resetPassword(@PathVariable Long id,
                                         @Valid @RequestBody EmployeePasswordDTO dto) {
        employeeService.resetPassword(id, dto);
        return ApiResult.ok();
    }
}
