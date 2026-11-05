package com.usedcar.vehicle.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * 车辆基础信息修改入参。
 *
 * 刻意不包含这几个字段，每一个都是有意排除的：
 *   purchasePrice / purchaseDate → 改了会破坏资金成本的计算基准，要改必须走「冲正」流程
 *   listPrice                   → 调价必须留下 price_change_log，走独立接口
 *   status                      → 只能走状态机接口，不能顺手改
 * 换句话说，这个 DTO 只允许改「描述性信息」，不允许改「钱和状态」。
 */
@Schema(description = "车辆基础信息修改请求")
public record VehicleUpdateDTO(

        @Schema(description = "品牌")
        @NotBlank(message = "品牌不能为空")
        String brand,

        @Schema(description = "车型")
        @NotBlank(message = "车型不能为空")
        String model,

        @Schema(description = "车牌号") String plateNo,

        @Schema(description = "车架号") String vin,

        @Schema(description = "首次上牌日期") LocalDate firstRegDate,

        @Schema(description = "表显里程")
        @Min(value = 0, message = "里程不能为负数")
        Integer mileage,

        @Schema(description = "负责销售") Long ownerUserId,

        @Schema(description = "备注") String remark
) {
}
