package com.usedcar.common;

/**
 * 业务异常。
 *
 * 为什么要自定义：业务规则不通过（例如「已成交的车不能再挂牌」）不是系统故障，
 * 不该抛 500。抛这个异常，由 GlobalExceptionHandler 统一转成 HTTP 200 + code=40x，
 * 前端拿到的是明确提示，而不是一堆堆栈。
 *
 * 继承 RuntimeException 而不是 Exception：Spring 的事务只对 RuntimeException 回滚，
 * 受检异常需要额外配置 rollbackFor，容易踩坑。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
