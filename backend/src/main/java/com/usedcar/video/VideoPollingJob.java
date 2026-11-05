package com.usedcar.video;

import com.usedcar.video.domain.VideoTask;
import com.usedcar.video.mapper.VideoTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频任务轮询与启动补偿。
 *
 * 整个异步架构的驱动源：
 *   提交 → 线程池（一次性）
 *   轮询 / 下载 / 合成 → 这个定时任务（每 5 秒扫一次到期任务）
 *
 * 为什么不用「一个线程从头等到尾」：
 *   图生视频单次 1~3 分钟，10 个并发就把线程池占满；而且服务重启后线程消失，任务永久丢失。
 *   改成「状态入库 + 定时扫表推进」，重启后扫一遍就能接着跑，这是可恢复的前提。
 */
@Component
public class VideoPollingJob {

    private static final Logger log = LoggerFactory.getLogger(VideoPollingJob.class);

    /** 与 VideoTaskService 里的提交超时保持一致：PENDING 超过这个时长就认为提交没成功 */
    private static final java.time.Duration SUBMIT_TIMEOUT = java.time.Duration.ofMinutes(2);

    private final VideoTaskMapper videoTaskMapper;
    private final VideoTaskService videoTaskService;

    public VideoPollingJob(VideoTaskMapper videoTaskMapper, VideoTaskService videoTaskService) {
        this.videoTaskMapper = videoTaskMapper;
        this.videoTaskService = videoTaskService;
    }

    /**
     * 每 5 秒推进一批到期任务。
     *
     * fixedDelay 而不是 fixedRate：一次扫描里如果 ffmpeg 合成了 40 秒，
     * fixedRate 会立刻补跑一轮造成堆积，fixedDelay 则是这一轮结束后再等 5 秒。
     */
    @Scheduled(fixedDelay = 5_000L, initialDelay = 10_000L)
    public void poll() {
        LocalDateTime now = LocalDateTime.now();
        // 停在 PENDING 超过 2 分钟的任务，说明异步提交没成功，捡回来重试
        LocalDateTime staleBefore = now.minus(SUBMIT_TIMEOUT);
        List<VideoTask> due = videoTaskMapper.selectDueTasks(now, staleBefore);
        if (due.isEmpty()) {
            return;
        }
        log.debug("本轮待推进任务 {} 个", due.size());
        for (VideoTask task : due) {
            // 单个任务失败不能中断整批：step 内部已捕获异常并转成 markFailure
            videoTaskService.step(task);
        }
    }

    /**
     * 启动补偿。放在 ApplicationReadyEvent，保证数据源、线程池都已就绪。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void compensate() {
        videoTaskService.compensateOnStartup();
    }
}
