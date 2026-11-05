package com.usedcar.video;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 任务提交的执行者。
 *
 * 单独抽成一个 Bean，是为了让 @Async 生效。
 * Spring 的 @Async 依赖代理：只有当调用方拿到的是代理对象时才会切到线程池。
 * 如果写成 VideoTaskService 里的私有方法或者 this.doSubmit()，调用走的是 this 引用，
 * 完全不经过代理，@Async 形同虚设——这是排查「异步不异步」时第一个要看的地方。
 */
@Component
public class VideoSubmitWorker {

    private final VideoTaskService videoTaskService;

    public VideoSubmitWorker(VideoTaskService videoTaskService) {
        this.videoTaskService = videoTaskService;
    }

    /**
     * 监听任务创建事件，丢到 videoExecutor 线程池执行。
     *
     * 必须是 @TransactionalEventListener(AFTER_COMMIT)，不能是普通的 @EventListener。
     * 原因（真实踩过）：事件是在 submit() 的 @Transactional 里发的，
     *   普通 @EventListener + @Async 会在事务还没提交时就把事件交给另一个线程，
     *   那个线程 selectById 读不到刚插入的行（READ COMMITTED 看不到未提交数据），
     *   于是 doSubmit 直接 return，任务永远停在 PENDING。
     *   现象是「接口返回了任务 id，但状态再也不动」，日志里连一条提交记录都没有。
     *
     * fallbackExecution = true：重试路径可能在事务外发事件，此时也要执行。
     */
    @Async("videoExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTaskCreated(VideoTaskCreatedEvent event) {
        videoTaskService.doSubmit(event.taskId());
    }
}
