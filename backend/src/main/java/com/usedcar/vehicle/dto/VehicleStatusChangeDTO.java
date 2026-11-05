package com.usedcar.vehicle.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 状态流转请求。
 *
 * targetStatus 用 String 接收而不是直接声明成 VehicleStatus 枚举：
 * 如果声明成枚举，传一个非法值（比如 "SOLDX"）会在 JSON 反序列化阶段就抛
 * HttpMessageNotReadableException，前端拿到的是一堆框架堆栈；
 * 用 String 接住，再在 Service 里显式判断，才能返回一句人话。
 */
@Schema(description = "状态流转请求")
public record VehicleStatusChangeDTO(

        @Schema(description = "目标状态", example = "ON_SALE",
                allowableValues = {"PURCHASED", "ON_SALE", "SOLD", "AFTER_SALE", "CLEARED"})
        @NotBlank(message = "目标状态不能为空")
        String targetStatus,

        @Schema(description = "变更说明，会写进状态日志") String remark
) {
}
