package com.usedcar.vehicle.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "调整挂牌价请求")
public record VehiclePriceChangeDTO(
        @Schema(description = "新的挂牌价，单位元", example = "86800.00")
        @NotNull(message = "新的挂牌价不能为空")
        @DecimalMin(value = "0.01", message = "挂牌价必须大于 0")
        BigDecimal newPrice,

        @Schema(description = "调价原因", example = "MANUAL")
        @Size(max = 100, message = "调价原因不能超过 100 个字符")
        String reason
) {
}
