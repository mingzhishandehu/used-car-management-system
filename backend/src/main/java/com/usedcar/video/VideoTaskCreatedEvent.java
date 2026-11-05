package com.usedcar.video;

/**
 * 视频任务创建事件。
 *
 * 为什么用事件而不是直接调异步方法：
 *   Spring 的 @Async 走的是代理，同一个类内部调用自己的 @Async 方法不会走代理，会变成同步执行——
 *   这是最容易被忽略的一个坑，表现为「明明加了 @Async，接口还是卡住」。
 *   用 ApplicationEventPublisher 发事件、由另一个 Bean 的 @EventListener + @Async 接收，
 *   天然跨 Bean，不存在自调用问题；重试时也只要重发一次同一个事件。
 */
public record VideoTaskCreatedEvent(Long taskId) {
}
