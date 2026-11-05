package com.usedcar.video.provider;

import com.usedcar.video.compose.FfmpegRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 占位视频供应商。
 *
 * 存在的意义：真实的 Seedance / 聆眸需要火山引擎 AK/SK 和真实计费，当前没有账号。
 * 但整条异步链路（提交 → 轮询 → 下载 → 合成）不需要真实供应商就能跑通并验证，
 * 所以这里用一个行为相同的假实现：
 *   - submit 返回一个 UUID，等价于上游的 task_id
 *   - poll 在前 6 秒返回 PROCESSING，之后用本机 FFmpeg 生成一段 6 秒的深色底片，返回 DONE
 *   - 文案里带 FAIL 关键字时返回 FAILED，专门用来验证「重试 3 次后转 FAILED」
 *
 * 关闭方式：application.yml 里设 app.video.provider=seedance，本 Bean 就不注册了。
 */
@Component
@ConditionalOnProperty(name = "app.video.provider", havingValue = "mock", matchIfMissing = true)
public class MockVideoProvider implements VideoProvider {

    private static final Logger log = LoggerFactory.getLogger(MockVideoProvider.class);

    /** 模拟上游生成耗时 */
    private static final Duration GENERATE_TIME = Duration.ofSeconds(6);

    /**
     * submitId → 提交时刻。
     * 用 ConcurrentHashMap：轮询是多线程的，普通 HashMap 会有可见性问题。
     */
    private final Map<String, Instant> submittedAt = new ConcurrentHashMap<>();

    /** submitId → 是否需要模拟失败（文案里含 FAIL 时置位，用来验证重试链路） */
    private final Map<String, Boolean> simulateFail = new ConcurrentHashMap<>();

    private final FfmpegRunner ffmpegRunner;
    private final Path rawDir;

    public MockVideoProvider(FfmpegRunner ffmpegRunner,
                             @Value("${app.video.work-dir:./uploads/videos}") String workDir) {
        this.ffmpegRunner = ffmpegRunner;
        this.rawDir = FfmpegRunner.ensureDir(Path.of(workDir, "raw"));
    }

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public SubmitResult submit(String imagePrompt, String copy) {
        // 模拟一次网络往返
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String submitId = "mock-" + UUID.randomUUID();
        submittedAt.put(submitId, Instant.now());
        // 文案或卖点里带 FAIL 时，这条任务的上游必定失败，专门用来验证「重试 3 次后转 FAILED」
        if ((copy != null && copy.contains("FAIL")) || (imagePrompt != null && imagePrompt.contains("FAIL"))) {
            simulateFail.put(submitId, Boolean.TRUE);
        }
        log.info("MOCK 提交成功, submitId={}", submitId);
        return SubmitResult.ok(submitId);
    }

    @Override
    public PollResult poll(String submitId) {
        // 服务重启后内存里的 map 会丢，这时按「刚刚提交」重新计时，
        // 而不是直接判失败——真实上游的任务本来也还在跑，只是本地丢了提交时刻
        Instant start = submittedAt.computeIfAbsent(submitId, k -> Instant.now());

        if (Duration.between(start, Instant.now()).compareTo(GENERATE_TIME) < 0) {
            return PollResult.processing();
        }

        if (Boolean.TRUE.equals(simulateFail.get(submitId))) {
            return PollResult.failed("MOCK 模拟上游失败（文案含 FAIL）");
        }

        Path target = rawDir.resolve(submitId + ".mp4");
        if (!Files.exists(target)) {
            try {
                // 生成一段 6 秒的深色底片，尺寸按短视频竖屏之外的通用横屏 16:9，
                // 字幕由合成阶段叠加，这里只当"上游产出的原始素材"
                ffmpegRunner.run(java.util.List.of(
                        "-y", "-hide_banner", "-loglevel", "error",
                        "-f", "lavfi",
                        "-i", "color=c=0x111827:s=1280x720:d=6",
                        "-c:v", "libx264",
                        "-pix_fmt", "yuv420p",
                        target.toAbsolutePath().toString()));
            } catch (Exception e) {
                log.error("MOCK 生成素材失败", e);
                return PollResult.failed("生成素材失败: " + e.getMessage());
            }
        }
        return PollResult.done(target.toAbsolutePath().toString());
    }
}
