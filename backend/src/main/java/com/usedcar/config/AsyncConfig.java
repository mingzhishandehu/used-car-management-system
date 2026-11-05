package com.usedcar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池。
 *
 * 三个参数是刻意的，不是随手填的：
 *   1. 队列有界（50）。无界队列在任务堆积时不会拒绝，只会把内存吃光然后 OOM，
 *      而且用户看到的是「一直排队、永远不失败」，比直接拒绝更糟。
 *   2. 拒绝策略用 CallerRunsPolicy。队列满了就让提交任务的线程自己跑，
 *      形成背压——上游会被自然压慢，而不是静默丢任务。
 *   3. 线程名前缀 video-。出问题时 jstack 一眼就能看出哪些线程在跑视频任务。
 *
 * 注意：这个线程池只负责「提交」这一步，提交完就把控制权交回数据库状态表。
 * 轮询、下载、合成都由定时任务驱动，不长期占用线程——真实图生视频一次要 1~3 分钟，
 * 用线程池死等会瞬间打满。
 */
@Configuration
public class AsyncConfig {

    @Bean("videoExecutor")
    public ThreadPoolTaskExecutor videoExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("video-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 停机时等在途任务跑完，别把提交到一半的任务直接掐死
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
