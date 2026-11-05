package com.usedcar.video.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频生成任务，对应 video_task 表。
 *
 * 这张表是整个异步架构的地基。没有它，@Async 的任务只活在内存里，
 * 服务一重启，所有排队和生成中的任务凭空消失，用户那边永远停在「生成中」。
 *
 * 四个字段分别解决四个问题：
 *   status        重启后扫表补偿的依据
 *   submitId      向上游轮询的凭证
 *   retryCount    失败重试次数，到上限转 FAILED，不再无限重试烧钱
 *   nextPollAt    分散轮询时刻，避免所有任务同一秒打满上游
 */
@Data
@TableName("video_task")
public class VideoTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务任务号，前缀 VT */
    private String taskNo;

    private Long vehicleId;

    /** 幂等键：vehicleId + 文案 hash，配合 uk_active_dedup 挡住重复提交 */
    private String dedupKey;

    /** 原始输入（车辆卖点） */
    private String sourceText;

    /** AI 改写后的口播文案 */
    private String marketingCopy;

    /** 实际送入 TTS 的文本 */
    private String ttsText;

    /** 见 VideoTaskStatus */
    private String status;

    /** 视频供应商：SEEDANCE / MOCK */
    private String provider;

    /** 上游任务 ID */
    private String submitId;

    /** 上游产物地址 */
    private String videoUrl;

    /** 已重试次数 */
    private Integer retryCount;

    private String failReason;

    /** 下次轮询时间 */
    private LocalDateTime nextPollAt;

    /** 提交上游时间，用于算提交超时 */
    private LocalDateTime submittedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
