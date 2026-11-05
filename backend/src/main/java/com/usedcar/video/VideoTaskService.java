package com.usedcar.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.usedcar.common.BizException;
import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.mapper.VehicleMapper;
import com.usedcar.video.compose.FfmpegComposer;
import com.usedcar.video.compose.FfmpegRunner;
import com.usedcar.video.copy.CopyWriter;
import com.usedcar.video.domain.VideoAsset;
import com.usedcar.video.domain.VideoTask;
import com.usedcar.video.domain.VideoTaskStatus;
import com.usedcar.video.mapper.VideoAssetMapper;
import com.usedcar.video.mapper.VideoTaskMapper;
import com.usedcar.video.provider.VideoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * 视频任务的核心状态机。
 *
 * 整个异步架构靠这张表 + 一个轮询任务驱动，而不是靠线程池里的线程一直等着。
 * 线程池只负责「提交」这一下，提交完立刻释放线程——图生视频一次要 1~3 分钟，
 * 用线程死等，10 个并发就能把池子占满，第 11 个请求连排队机会都没有。
 */
@Service
public class VideoTaskService {

    private static final Logger log = LoggerFactory.getLogger(VideoTaskService.class);

    /** 失败后最多重试 3 次，等于最多花 4 次调用成本 */
    private static final int MAX_RETRY = 3;

    /** 三档超时：卡在哪个阶段就用哪一档，不能一刀切 */
    private static final Duration SUBMIT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration GENERATE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration COMPOSE_TIMEOUT = Duration.ofMinutes(3);

    /** 轮询间隔：太密会打满上游并触发限流，太疏用户等得久 */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private static final DateTimeFormatter TASK_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VideoTaskMapper videoTaskMapper;
    private final VideoAssetMapper videoAssetMapper;
    private final VehicleMapper vehicleMapper;
    private final VideoProvider videoProvider;
    private final CopyWriter copyWriter;
    private final FfmpegComposer ffmpegComposer;
    private final ApplicationEventPublisher eventPublisher;
    private final Path workRoot;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public VideoTaskService(VideoTaskMapper videoTaskMapper,
                            VideoAssetMapper videoAssetMapper,
                            VehicleMapper vehicleMapper,
                            VideoProvider videoProvider,
                            CopyWriter copyWriter,
                            FfmpegComposer ffmpegComposer,
                            ApplicationEventPublisher eventPublisher,
                            @Value("${app.video.work-dir:./uploads/videos}") String workDir) {
        this.videoTaskMapper = videoTaskMapper;
        this.videoAssetMapper = videoAssetMapper;
        this.vehicleMapper = vehicleMapper;
        this.videoProvider = videoProvider;
        this.copyWriter = copyWriter;
        this.ffmpegComposer = ffmpegComposer;
        this.eventPublisher = eventPublisher;
        this.workRoot = Path.of(workDir);
    }

    // ==================== 提交 ====================

    /**
     * 创建任务并异步提交。
     *
     * 幂等：同一辆车 + 同一段卖点，如果已经有进行中的任务，直接返回那个任务的 id，
     * 不重复下单、不重复烧钱。靠 uk_active_dedup 唯一索引兜底，
     * 两个请求同时进来时只有一个能插入成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long vehicleId, String sourceText) {
        Vehicle vehicle = vehicleMapper.selectById(vehicleId);
        if (vehicle == null) {
            throw new BizException(404, "车辆不存在");
        }
        if ("CLEARED".equals(vehicle.getStatus())) {
            throw new BizException(400, "已清库的车辆不再生成营销视频");
        }

        String text = (sourceText == null || sourceText.isBlank())
                ? vehicle.getBrand() + " " + vehicle.getModel()
                : sourceText.trim();

        String dedupKey = vehicleId + ":" + md5(text);

        VideoTask active = videoTaskMapper.selectOne(new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getDedupKey, dedupKey)
                .in(VideoTask::getStatus, "PENDING", "SUBMITTED", "PROCESSING", "DOWNLOADING", "COMPOSING")
                .last("LIMIT 1"));
        if (active != null) {
            log.info("已存在进行中的任务，直接复用: taskNo={}", active.getTaskNo());
            return active.getId();
        }

        VideoTask task = new VideoTask();
        task.setTaskNo("VT" + LocalDateTime.now().format(TASK_NO_FORMAT)
                + String.format("%03d", (int) (Math.random() * 1000)));
        task.setVehicleId(vehicleId);
        task.setDedupKey(dedupKey);
        task.setSourceText(text);
        task.setStatus(VideoTaskStatus.PENDING.name());
        task.setProvider(videoProvider.name());
        task.setRetryCount(0);

        try {
            videoTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求刚刚插入成功，回查它
            VideoTask existed = videoTaskMapper.selectOne(new LambdaQueryWrapper<VideoTask>()
                    .eq(VideoTask::getDedupKey, dedupKey)
                    .in(VideoTask::getStatus, "PENDING", "SUBMITTED", "PROCESSING", "DOWNLOADING", "COMPOSING")
                    .last("LIMIT 1"));
            if (existed != null) {
                return existed.getId();
            }
            throw new BizException(500, "任务创建失败，请重试");
        }

        // 事务提交后再触发异步，否则异步线程可能读不到这条还没提交的记录
        eventPublisher.publishEvent(new VideoTaskCreatedEvent(task.getId()));
        log.info("视频任务已创建: {} vehicle={}", task.getTaskNo(), vehicleId);
        return task.getId();
    }

    /**
     * 真正执行提交。由 VideoSubmitWorker 的异步监听器调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void doSubmit(Long taskId) {
        VideoTask task = videoTaskMapper.selectById(taskId);
        if (task == null || !VideoTaskStatus.PENDING.name().equals(task.getStatus())) {
            return;
        }

        // 文案在这一步生成：AI 改写要 2~5 秒，放在 HTTP 请求线程里会拖慢接口响应
        if (task.getMarketingCopy() == null || task.getMarketingCopy().isBlank()) {
            Vehicle vehicle = vehicleMapper.selectById(task.getVehicleId());
            String copy = copyWriter.generate(vehicle, task.getSourceText());
            VideoTask copyPatch = new VideoTask();
            copyPatch.setId(taskId);
            copyPatch.setMarketingCopy(copy);
            copyPatch.setTtsText(copy);
            videoTaskMapper.updateById(copyPatch);
            task.setMarketingCopy(copy);
        }

        VideoProvider.SubmitResult result = videoProvider.submit(task.getSourceText(), task.getMarketingCopy());
        if (!result.success()) {
            markFailure(task, "提交上游失败: " + result.error());
            return;
        }

        VideoTask patch = new VideoTask();
        patch.setId(taskId);
        patch.setStatus(VideoTaskStatus.SUBMITTED.name());
        patch.setSubmitId(result.submitId());
        patch.setSubmittedAt(LocalDateTime.now());
        patch.setNextPollAt(LocalDateTime.now().plus(POLL_INTERVAL));
        videoTaskMapper.updateById(patch);
        log.info("任务 {} 已提交上游, submitId={}", task.getTaskNo(), result.submitId());
    }

    // ==================== 推进 ====================

    /**
     * 推进一步。由轮询任务对每个到期任务调用。
     *
     * 注意这里是「一步」，不是「一直跑到结束」——跑到底会让一次轮询占住线程几分钟。
     */
    @Transactional(rollbackFor = Exception.class)
    public void step(VideoTask task) {
        VideoTaskStatus status = VideoTaskStatus.valueOf(task.getStatus());
        try {
            switch (status) {
                case PENDING -> {
                    // 停在 PENDING 说明异步提交没落地（线程池拒绝、进程重启、事件丢失）。
                    // 超过提交超时就当作一次失败，交给重试逻辑处理，而不是永远等下去。
                    if (isTimeout(task.getCreatedAt(), SUBMIT_TIMEOUT)) {
                        markFailure(task, "提交超时（超过 " + SUBMIT_TIMEOUT.toMinutes() + " 分钟仍未提交上游）");
                    }
                }
                case SUBMITTED, PROCESSING -> pollUpstream(task);
                case DOWNLOADING -> download(task);
                case COMPOSING -> compose(task);
                default -> {
                    // 终态不需要处理
                }
            }
        } catch (Exception e) {
            log.error("任务 {} 推进失败", task.getTaskNo(), e);
            markFailure(task, "推进失败: " + e.getMessage());
        }
    }

    private void pollUpstream(VideoTask task) {
        if (isTimeout(task.getSubmittedAt(), GENERATE_TIMEOUT)) {
            markFailure(task, "上游生成超时（超过 " + GENERATE_TIMEOUT.toMinutes() + " 分钟）");
            return;
        }

        VideoProvider.PollResult result = videoProvider.poll(task.getSubmitId());
        switch (result.state()) {
            case PROCESSING -> {
                VideoTask patch = new VideoTask();
                patch.setId(task.getId());
                // SUBMITTED 第一次轮询后正式进入 PROCESSING
                patch.setStatus(VideoTaskStatus.PROCESSING.name());
                patch.setNextPollAt(LocalDateTime.now().plus(POLL_INTERVAL));
                videoTaskMapper.updateById(patch);
            }
            case DONE -> {
                VideoTask patch = new VideoTask();
                patch.setId(task.getId());
                patch.setStatus(VideoTaskStatus.DOWNLOADING.name());
                patch.setVideoUrl(result.videoUrl());
                patch.setNextPollAt(LocalDateTime.now());
                videoTaskMapper.updateById(patch);
                log.info("任务 {} 上游生成完成，进入下载", task.getTaskNo());
            }
            case FAILED -> markFailure(task, "上游返回失败: " + result.error());
        }
    }

    private void download(VideoTask task) {
        if (isTimeout(task.getUpdatedAt(), DOWNLOAD_TIMEOUT)) {
            markFailure(task, "下载超时（超过 " + DOWNLOAD_TIMEOUT.toMinutes() + " 分钟）");
            return;
        }

        Path taskDir = FfmpegRunner.ensureDir(workRoot.resolve(String.valueOf(task.getId())));
        Path local = taskDir.resolve("raw.mp4");
        try {
            String url = task.getVideoUrl();
            if (url == null || url.isBlank()) {
                throw new IOException("上游未返回素材地址");
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                downloadHttp(url, local);
            } else {
                // 占位供应商给的是本机路径，直接复制
                Files.copy(Path.of(url), local, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            markFailure(task, "下载素材失败: " + e.getMessage());
            return;
        }

        recordAsset(task.getId(), "RAW", local.toAbsolutePath().toString());

        VideoTask patch = new VideoTask();
        patch.setId(task.getId());
        patch.setStatus(VideoTaskStatus.COMPOSING.name());
        patch.setNextPollAt(LocalDateTime.now());
        videoTaskMapper.updateById(patch);
    }

    private void compose(VideoTask task) {
        if (isTimeout(task.getUpdatedAt(), COMPOSE_TIMEOUT)) {
            markFailure(task, "合成超时（超过 " + COMPOSE_TIMEOUT.toMinutes() + " 分钟）");
            return;
        }

        Path raw = workRoot.resolve(String.valueOf(task.getId())).resolve("raw.mp4");
        String copy = task.getMarketingCopy() == null ? task.getSourceText() : task.getMarketingCopy();

        Path finalVideo;
        try {
            finalVideo = ffmpegComposer.compose(task.getId(), raw, copy);
        } catch (Exception e) {
            markFailure(task, "合成失败: " + e.getMessage());
            return;
        }

        recordAsset(task.getId(), "FINAL", finalVideo.toAbsolutePath().toString());

        VideoTask patch = new VideoTask();
        patch.setId(task.getId());
        patch.setStatus(VideoTaskStatus.SUCCESS.name());
        patch.setFinishedAt(LocalDateTime.now());
        patch.setFailReason(null);
        videoTaskMapper.updateById(patch);
        log.info("任务 {} 完成: {}", task.getTaskNo(), finalVideo);
    }

    // ==================== 失败与补偿 ====================

    /**
     * 失败处理：还有重试次数就退回 PENDING 重来，否则落到 FAILED 终态。
     *
     * 关键点：重试不是原地重试，而是把状态改回 PENDING 并重新发事件，
     * 这样整条链路重新走一遍，不会出现「只重试了一半」的中间态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markFailure(VideoTask task, String reason) {
        int retry = task.getRetryCount() == null ? 0 : task.getRetryCount();

        VideoTask patch = new VideoTask();
        patch.setId(task.getId());
        patch.setFailReason(reason.length() > 480 ? reason.substring(0, 480) : reason);

        if (retry < MAX_RETRY) {
            patch.setStatus(VideoTaskStatus.PENDING.name());
            patch.setRetryCount(retry + 1);
            patch.setSubmitId(null);
            patch.setNextPollAt(null);
            patch.setSubmittedAt(null);
            videoTaskMapper.updateById(patch);
            log.warn("任务 {} 第 {} 次失败，准备重试: {}", task.getTaskNo(), retry + 1, reason);
            eventPublisher.publishEvent(new VideoTaskCreatedEvent(task.getId()));
        } else {
            patch.setStatus(VideoTaskStatus.FAILED.name());
            patch.setFinishedAt(LocalDateTime.now());
            videoTaskMapper.updateById(patch);
            log.error("任务 {} 重试 {} 次仍失败，转 FAILED: {}", task.getTaskNo(), MAX_RETRY, reason);
        }
    }

    /**
     * 启动补偿。
     *
     * 服务重启时，内存里的线程池和轮询进度全部丢失，但数据库里可能有一批任务卡在
     * SUBMITTED / PROCESSING / DOWNLOADING / COMPOSING。不处理它们就会永远停在中间态。
     * 补偿策略：PENDING 的重新发事件，其余的把 next_poll_at 提前到当前时刻，交给轮询任务继续推。
     */
    public void compensateOnStartup() {
        List<VideoTask> tasks = videoTaskMapper.selectInFlight();
        if (tasks.isEmpty()) {
            return;
        }

        int republish = 0;
        int reschedule = 0;
        for (VideoTask task : tasks) {
            if (VideoTaskStatus.PENDING.name().equals(task.getStatus())) {
                eventPublisher.publishEvent(new VideoTaskCreatedEvent(task.getId()));
                republish++;
            } else {
                VideoTask patch = new VideoTask();
                patch.setId(task.getId());
                patch.setNextPollAt(LocalDateTime.now());
                videoTaskMapper.updateById(patch);
                reschedule++;
            }
        }
        log.warn("启动补偿：{} 个任务重新提交，{} 个任务重新纳入轮询", republish, reschedule);
    }

    // ==================== 查询 ====================

    public VideoTask detail(Long taskId) {
        VideoTask task = videoTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(404, "任务不存在");
        }
        return task;
    }

    public List<VideoAsset> assets(Long taskId) {
        return videoAssetMapper.selectList(new LambdaQueryWrapper<VideoAsset>()
                .eq(VideoAsset::getTaskId, taskId)
                .orderByAsc(VideoAsset::getId));
    }

    public List<VideoTask> listByVehicle(Long vehicleId) {
        return videoTaskMapper.selectList(new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getVehicleId, vehicleId)
                .orderByDesc(VideoTask::getId));
    }

    // ==================== 内部 ====================

    private void recordAsset(Long taskId, String type, String url) {
        VideoAsset asset = new VideoAsset();
        asset.setTaskId(taskId);
        asset.setAssetType(type);
        asset.setUrl(url);
        try {
            Path p = Path.of(url);
            if (Files.exists(p)) {
                asset.setSizeBytes(Files.size(p));
            }
        } catch (Exception ignored) {
            // 拿不到文件大小不影响主流程
        }
        videoAssetMapper.insert(asset);
    }

    private boolean isTimeout(LocalDateTime start, Duration timeout) {
        return start != null && start.plus(timeout).isBefore(LocalDateTime.now());
    }

    private void downloadHttp(String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // MD5 用不了说明 JDK 环境异常，退化成 hashCode，宁可碰撞也不能让提交失败
            return Integer.toHexString(text.hashCode());
        }
    }
}
