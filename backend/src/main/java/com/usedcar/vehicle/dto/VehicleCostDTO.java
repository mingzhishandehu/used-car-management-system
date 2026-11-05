package com.usedcar.vehicle.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 追加一笔成本。
 *
 * 收车款在「收车入库」时已自动写入，这里用于补录整备、运输、过户等后续发生的费用。
 */
@Schema(description = "追加成本请求")
public record VehicleCostDTO(

        @Schema(description = "成本类型", example = "RECONDITION",
                allowableValues = {"PURCHASE", "RECONDITION", "TRANSPORT", "TRANSFER", "OTHER"})
        @NotBlank(message = "成本类型不能为空")
        String costType,

        @Schema(description = "金额，单位元", example = "1200.00")
        @NotNull(message = "金额不能为空")
        @DecimalMin(value = "0.01", message = "金额必须大于 0")
        BigDecimal amount,

        @Schema(description = "发生日期，不传默认今天") LocalDate occurDate,

        @Schema(description = "说明") String remark
) {
}
