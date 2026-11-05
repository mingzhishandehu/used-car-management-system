package com.usedcar.video.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频产物，对应 video_asset 表。
 *
 * 与 video_task 拆开的原因：一次任务会产出多个文件（上游原始视频、配音、字幕文件、成片），
 * 重试之后还可能再产出一整套。全塞进 video_task 就是一堆 asset1_url / asset2_url 的列。
 */
@Data
@TableName("video_asset")
public class VideoAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** RAW 上游原始 / AUDIO 配音 / SUBTITLE 字幕文件 / FINAL 成片 */
    private String assetType;

    private String url;

    private Integer duration;

    private Long sizeBytes;

    private LocalDateTime createdAt;
}
