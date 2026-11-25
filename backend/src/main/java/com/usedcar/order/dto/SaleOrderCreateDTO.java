package com.usedcar.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 成交登记入参。客户和成交价必填，其余可空。 */
public record SaleOrderCreateDTO(
        @NotNull(message = "必须选择买方客户") Long customerId,
        @NotNull(message = "成交价不能为空")
        @DecimalMin(value = "0.01", message = "成交价必须大于 0") BigDecimal dealPrice,
        @NotNull(message = "成交日期不能为空") LocalDate dealDate,
        String payType,
        BigDecimal deposit,
        String remark
) {
}
