package com.usedcar.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 新增客户入参。
 *
 * customerType / level 用 String 接而不是枚举，理由和 VehicleStatusChangeDTO 一样：
 * 声明成枚举时，非法值会在 JSON 反序列化阶段抛 HttpMessageNotReadableException，
 * 前端拿到的是框架堆栈；用 String 接住再由 Service 显式校验，才能返回一句人话。
 */
@Schema(description = "新增客户请求")
public record CustomerCreateDTO(

        @Schema(description = "客户姓名", example = "张伟")
        @NotBlank(message = "客户姓名不能为空")
        String name,

        @Schema(description = "手机号（查重主依据）", example = "13800000009")
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Schema(description = "身份证号，成交过户时必填，录入阶段可空") String idCard,

        @Schema(description = "客户类型", example = "BUYER",
                allowableValues = {"BUYER", "SELLER", "BOTH"})
        String customerType,

        @Schema(description = "客户等级", example = "NORMAL",
                allowableValues = {"NORMAL", "VIP"})
        String level,

        @Schema(description = "来源渠道", example = "抖音") String source,

        @Schema(description = "备注") String remark
) {
}
