package com.usedcar.system.dto;

import java.time.LocalDateTime;

/**
 * 员工列表/详情出参。
 *
 * 刻意不复用 SysUser 这个实体：实体里带 password 字段，
 * 哪怕在 Controller 里 setPassword(null)，只要有人以后忘了这一句，
 * BCrypt 密文就会直接出网。用独立 VO 从结构上保证密码不可能被序列化。
 *
 * status 是登录开关，employmentStatus 是人事状态，两个都要返回给前端分别展示。
 */
public record EmployeeVO(
        Long id,
        String username,
        String realName,
        String phone,
        String role,
        Integer status,
        String employmentStatus,
        LocalDateTime createdAt
) {
}
