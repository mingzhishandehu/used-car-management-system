package com.usedcar.video.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 视频任务状态机。
 *
 * 全流程：
 *   PENDING 待提交 → SUBMITTED 已提交 → PROCESSING 生成中 → DOWNLOADING 下载中 → COMPOSING 合成中 → SUCCESS 成功
 *   任何一步都可以转到 FAILED；FAILED 在还有重试次数时可以退回 PENDING 重来。
 *
 * 与车辆状态机一样，合法流转只在这一个地方定义一次，Service 不允许硬编码字符串比较。
 */
public enum VideoTaskStatus {

    PENDING("待提交"),
    SUBMITTED("已提交"),
    PROCESSING("生成中"),
    DOWNLOADING("下载中"),
    COMPOSING("合成中"),
    SUCCESS("成功"),
    FAILED("失败");

    private final String label;

    VideoTaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    private static final Map<VideoTaskStatus, Set<VideoTaskStatus>> ALLOWED;

    static {
        Map<VideoTaskStatus, Set<VideoTaskStatus>> map = new EnumMap<>(VideoTaskStatus.class);
        map.put(PENDING, EnumSet.of(SUBMITTED, FAILED));
        // 上游可能秒回结果，所以 SUBMITTED 允许直接跳到 DOWNLOADING，不必强行走 PROCESSING
        // 每个进行中状态都允许退回 PENDING：这是重试路径，由 Service 判断剩余次数
        map.put(SUBMITTED, EnumSet.of(PROCESSING, DOWNLOADING, FAILED, PENDING));
        // PROCESSING 包含自身：轮询时状态不变，只是刷新 next_poll_at
        map.put(PROCESSING, EnumSet.of(PROCESSING, DOWNLOADING, FAILED, PENDING));
        map.put(DOWNLOADING, EnumSet.of(COMPOSING, FAILED, PENDING));
        map.put(COMPOSING, EnumSet.of(SUCCESS, FAILED, PENDING));
        map.put(SUCCESS, Collections.emptySet());
        // 失败可以退回 PENDING 重试；是否还有次数由 Service 判断，枚举只管路径合法
        map.put(FAILED, EnumSet.of(PENDING));
        ALLOWED = Collections.unmodifiableMap(map);
    }

    public boolean canTransitionTo(VideoTaskStatus target) {
        return target != null && ALLOWED.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    /** 终态：不会再自动流转，也不会被补偿扫表捡起来 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }

    /** 进行中：服务重启后，这些状态的任务必须被补偿扫表重新捡起来 */
    public boolean isInFlight() {
        return !isTerminal();
    }
}
