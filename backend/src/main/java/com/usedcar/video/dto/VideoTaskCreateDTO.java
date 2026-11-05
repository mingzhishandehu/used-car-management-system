package com.usedcar.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 提交视频生成任务。
 */
@Schema(description = "提交视频任务请求")
public record VideoTaskCreateDTO(

        @Schema(description = "车辆 id", example = "3")
        @NotNull(message = "车辆 id 不能为空")
        Long vehicleId,

        @Schema(description = "销售补充卖点，可留空。留空时用品牌车型作为原始输入")
        String sourceText
) {
}
