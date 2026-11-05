package com.usedcar.system;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.usedcar.common.ApiResult;
import com.usedcar.system.domain.SysUser;
import com.usedcar.system.dto.LoginDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录接口。
 *
 * 路径设计：/api/auth/login 在 SaTokenConfig 里是白名单，其余三个都要登录态。
 */
@Tag(name = "登录鉴权")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录。成功返回 token，前端后续请求放进请求头 satoken 字段。
     *
     * @Valid 必须加，否则 LoginDTO 上的 @NotBlank 不会生效
     */
    @Operation(summary = "登录")
    @PostMapping("/login")
    public ApiResult<SaTokenInfo> login(@Valid @RequestBody LoginDTO dto) {
        return ApiResult.ok(authService.login(dto));
    }

    /**
     * 当前登录人。前端刷新页面后用它恢复用户信息，避免把用户信息存 localStorage 被篡改。
     */
    @Operation(summary = "当前登录人")
    @GetMapping("/me")
    public ApiResult<SysUser> me() {
        return ApiResult.ok(authService.currentUser());
    }

    /**
     * 退出登录。Sa-Token 会把服务端的会话标记为注销，token 立即失效。
     */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public ApiResult<Void> logout() {
        StpUtil.logout();
        return ApiResult.ok();
    }
}
