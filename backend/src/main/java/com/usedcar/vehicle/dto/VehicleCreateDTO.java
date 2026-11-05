package com.usedcar.vehicle.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 收车入库入参。
 *
 * 只放「收车这一刻就能确定」的字段。status 不在这里传：
 * 新车的状态只能是 PURCHASED，让前端传状态等于把状态机交给客户端，是一类典型越权。
 */
@Schema(description = "收车入库请求")
public record VehicleCreateDTO(

        @Schema(description = "车架号 VIN，17 位") String vin,

        @Schema(description = "车牌号") String plateNo,

        @Schema(description = "品牌", example = "大众")
        @NotBlank(message = "品牌不能为空")
        String brand,

        @Schema(description = "车型", example = "朗逸 2021款 1.5L 自动舒适版")
        @NotBlank(message = "车型不能为空")
        String model,

        @Schema(description = "首次上牌日期") LocalDate firstRegDate,

        @Schema(description = "表显里程，单位 km")
        @Min(value = 0, message = "里程不能为负数")
        Integer mileage,

        @Schema(description = "收车价，单位元", example = "68000.00")
        @NotNull(message = "收车价不能为空")
        @DecimalMin(value = "0", message = "收车价不能为负数")
        BigDecimal purchasePrice,

        @Schema(description = "收车日期，资金成本从这天起算")
        @NotNull(message = "收车日期不能为空")
        LocalDate purchaseDate,

        @Schema(description = "挂牌价，收车时可以先不填，但转为在售前必须有值")
        @DecimalMin(value = "0", message = "挂牌价不能为负数")
        BigDecimal listPrice,

        @Schema(description = "负责销售，sys_user.id") Long ownerUserId,

        @Schema(description = "备注") String remark
) {
}
