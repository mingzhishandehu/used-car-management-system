package com.usedcar.approval.dto;

/**
 * 车辆写操作的统一返回。
 *
 * ADMIN 直接执行业务，executed=true、data 是原接口返回值；
 * 员工提交申请，executed=false、approvalId 是新申请单 id、data 为 null。
 *
 * 为什么统一包一层而不是让员工请求报错：员工在页面上点的是同一个「保存」按钮，
 * 区别只是结果变成「等审批」。前端只需判断 executed 决定要不要跳详情页，
 * 不用在每个页面里先查一次自己的角色再选接口。
 */
public record WriteResult(
        boolean executed,
        Long approvalId,
        Object data,
        String message
) {
    public static WriteResult executed(Object data) {
        return new WriteResult(true, null, data, "OK");
    }

    public static WriteResult pending(Long approvalId) {
        return new WriteResult(false, approvalId, null, "已提交申请，等待管理员审批");
    }
}
