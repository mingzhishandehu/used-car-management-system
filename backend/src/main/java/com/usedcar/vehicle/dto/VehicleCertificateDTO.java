package com.usedcar.vehicle.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * 新增证件入参。
 */
@Schema(description = "新增证件请求")
public record VehicleCertificateDTO(

        @Schema(description = "证件类型", example = "INSURANCE",
                allowableValues = {"REGISTER", "DRIVING", "INSURANCE", "INSPECT"})
        @NotBlank(message = "证件类型不能为空")
        String certType,

        @Schema(description = "证件编号") String certNo,

        @Schema(description = "到期日期，长期有效可不填") LocalDate expireDate,

        @Schema(description = "扫描件地址") String fileUrl
) {
}
