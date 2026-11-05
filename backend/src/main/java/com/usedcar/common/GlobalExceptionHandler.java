package com.usedcar.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * @RestControllerAdvice 会把这里的方法织入所有 @RestController，
 * 控制器里不用写 try-catch，抛出去就行。
 *
 * 处理顺序按「具体到宽泛」：越具体的异常类型先匹配。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务规则不通过，例如状态机拒绝流转。 */
    @ExceptionHandler(BizException.class)
    public ApiResult<Void> handleBiz(BizException e) {
        // 业务异常是预期内的，不打堆栈，只记一行，否则日志会被正常业务噪音淹没
        log.warn("业务异常: {}", e.getMessage());
        return ApiResult.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败，例如 @NotBlank 没通过。把字段名和原因拼给前端。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResult<Void> handleValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ApiResult.fail(400, detail.isEmpty() ? "参数校验失败" : detail);
    }

    /** 未登录。返回 401，前端据此跳登录页。 */
    @ExceptionHandler(NotLoginException.class)
    public ApiResult<Void> handleNotLogin(NotLoginException e) {
        return ApiResult.fail(401, "未登录或登录已过期");
    }

    /** 无权限。返回 403。 */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ApiResult<Void> handleNoPermission(Exception e) {
        return ApiResult.fail(403, "无权限执行该操作");
    }

    /** 兜底。未预料的异常必须打完整堆栈，否则线上无法定位。 */
    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleOther(Exception e) {
        log.error("未捕获异常", e);
        // 注意：不把 e.getMessage() 直接返回给前端，避免把 SQL、内网地址等泄漏出去
        return ApiResult.fail(500, "服务器内部错误");
    }
}
