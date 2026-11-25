package com.usedcar.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 修改员工入参。
 * 不含 username（不允许改）、不含 password（改密码走独立接口）、不含 status（走停用启用接口）。
 * 把这三样拆开，是为了让每个写操作都有明确的审计边界，不做「一个大 DTO 全都能改」。
 */
@Schema(description = "修改员工请求")
public record EmployeeUpdateDTO(

        @NotBlank(message = "真实姓名不能为空") String realName,

        String phone,

        @NotBlank(message = "角色不能为空") String role
) {
}
