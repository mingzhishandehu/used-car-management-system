package com.usedcar.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录入参。
 *
 * 用 record 的原因：这两个字段一旦建好就不该再改，record 天然不可变，
 * 也省掉 getter/setter。Controller 方法参数上加 @Valid 就能触发校验。
 */
@Schema(description = "登录请求")
public record LoginDTO(

        @Schema(description = "登录名", example = "admin")
        @NotBlank(message = "用户名不能为空")
        String username,

        @Schema(description = "明文密码，只在传输层出现，落库前会被 BCrypt 编码")
        @NotBlank(message = "密码不能为空")
        String password
) {
}
