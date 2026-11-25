package com.usedcar.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员重置某员工密码的入参。不允许员工自己改自己密码时绕过管理员。 */
public record EmployeePasswordDTO(
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需在 6~32 之间")
        String newPassword
) {
}
