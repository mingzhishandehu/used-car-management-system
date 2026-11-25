package com.usedcar.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新增员工入参。登录名建好后不允许改，改了会让历史流水对不上人。 */
@Schema(description = "新增员工请求")
public record EmployeeCreateDTO(

        @Schema(description = "登录名", example = "sales01")
        @NotBlank(message = "登录名不能为空")
        @Size(max = 50, message = "登录名最多 50 字符")
        String username,

        @Schema(description = "初始密码，明文只在传输层出现")
        @NotBlank(message = "初始密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需在 6~32 之间")
        String password,

        @Schema(description = "真实姓名")
        @NotBlank(message = "真实姓名不能为空")
        String realName,

        @Schema(description = "手机号") String phone,

        @Schema(description = "角色：ADMIN / SALES / FINANCE")
        @NotBlank(message = "角色不能为空")
        String role
) {
}
