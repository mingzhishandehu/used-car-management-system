package com.usedcar.common;

/**
 * 统一响应体。
 *
 * 用 record 而不是普通 class：四个字段全 final，Spring 反序列化（Java 16+ 支持 record）和
 * Jackson 序列化都能直接工作，不用写 getter/setter。
 *
 * code = 0 表示成功，非 0 表示失败。前端只判断 code，不判断 HTTP 状态码，
 * 这样 401/403/500 的业务含义都能在 body 里带出来。
 */
public record ApiResult<T>(int code, String message, T data) {

    /** 成功并带数据。 */
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "OK", data);
    }

    /** 成功但不带数据，例如删除操作。 */
    public static ApiResult<Void> ok() {
        return new ApiResult<>(0, "OK", null);
    }

    /** 失败，code 由调用方指定。 */
    public static <T> ApiResult<T> fail(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
