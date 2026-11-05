package com.usedcar.video.controller;

import com.usedcar.common.ApiResult;
import com.usedcar.video.VideoTaskService;
import com.usedcar.video.domain.VideoAsset;
import com.usedcar.video.domain.VideoTask;
import com.usedcar.video.dto.VideoTaskCreateDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 视频任务接口。
 *
 * 提交接口立即返回任务 id，不在请求线程里等生成完成——
 * 图生视频一次要 1~3 分钟，同步等会把 Tomcat 线程占满，前端也会超时。
 */
@Tag(name = "视频任务")
@RestController
@RequestMapping("/api/videos/tasks")
public class VideoController {

    private final VideoTaskService videoTaskService;

    public VideoController(VideoTaskService videoTaskService) {
        this.videoTaskService = videoTaskService;
    }

    /**
     * 提交任务。返回任务 id，前端拿它轮询进度。
     *
     * 幂等：同一辆车 + 同一段卖点已有进行中的任务时，直接返回那个任务的 id。
     */
    @Operation(summary = "提交视频生成任务")
    @PostMapping
    public ApiResult<Long> submit(@Valid @RequestBody VideoTaskCreateDTO dto) {
        return ApiResult.ok(videoTaskService.submit(dto.vehicleId(), dto.sourceText()));
    }

    @Operation(summary = "任务详情（前端轮询用）")
    @GetMapping("/{id}")
    public ApiResult<VideoTask> detail(@PathVariable Long id) {
        return ApiResult.ok(videoTaskService.detail(id));
    }

    @Operation(summary = "任务产物列表")
    @GetMapping("/{id}/assets")
    public ApiResult<List<VideoAsset>> assets(@PathVariable Long id) {
        return ApiResult.ok(videoTaskService.assets(id));
    }

    @Operation(summary = "某辆车的任务历史")
    @GetMapping
    public ApiResult<List<VideoTask>> listByVehicle(@RequestParam Long vehicleId) {
        return ApiResult.ok(videoTaskService.listByVehicle(vehicleId));
    }
}
