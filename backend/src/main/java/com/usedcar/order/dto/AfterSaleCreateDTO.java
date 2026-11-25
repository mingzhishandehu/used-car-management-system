package com.usedcar.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** 新增售后单入参。 */
public record AfterSaleCreateDTO(
        @NotBlank(message = "问题类型不能为空") String issueType,
        @NotBlank(message = "问题描述不能为空") String description,
        @NotNull(message = "售后成本不能为空，没有填 0")
        @DecimalMin(value = "0", message = "售后成本不能为负") BigDecimal cost,
        Long orderId
) {
}
