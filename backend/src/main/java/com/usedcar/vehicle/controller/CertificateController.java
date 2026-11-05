package com.usedcar.vehicle.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.usedcar.common.ApiResult;
import com.usedcar.common.BizException;
import com.usedcar.pricing.PricingRuleService;
import com.usedcar.vehicle.domain.VehicleCertificate;
import com.usedcar.vehicle.dto.VehicleCertificateDTO;
import com.usedcar.vehicle.mapper.VehicleCertificateMapper;
import com.usedcar.vehicle.mapper.VehicleMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 车辆证件接口。
 *
 * 路径挂在 /api/vehicles/{vehicleId}/certificates 下，表达「证件属于某辆车」这个从属关系。
 * 删除单独放在 /api/certificates/{id}，因为删除时只需要证件 id，不需要知道属于哪辆车。
 */
@Tag(name = "车辆证件")
@RestController
@RequestMapping("/api")
public class CertificateController {

    private final VehicleCertificateMapper certificateMapper;
    private final VehicleMapper vehicleMapper;
    private final PricingRuleService pricingRuleService;

    public CertificateController(VehicleCertificateMapper certificateMapper,
                                 VehicleMapper vehicleMapper,
                                 PricingRuleService pricingRuleService) {
        this.certificateMapper = certificateMapper;
        this.vehicleMapper = vehicleMapper;
        this.pricingRuleService = pricingRuleService;
    }

    @Operation(summary = "某辆车的证件列表")
    @GetMapping("/vehicles/{vehicleId}/certificates")
    public ApiResult<List<VehicleCertificate>> list(@PathVariable Long vehicleId) {
        return ApiResult.ok(certificateMapper.selectList(
                new LambdaQueryWrapper<VehicleCertificate>()
                        .eq(VehicleCertificate::getVehicleId, vehicleId)
                        .orderByAsc(VehicleCertificate::getExpireDate)));
    }

    @Operation(summary = "新增证件")
    @PostMapping("/vehicles/{vehicleId}/certificates")
    public ApiResult<Long> create(@PathVariable Long vehicleId,
                                  @Valid @RequestBody VehicleCertificateDTO dto) {
        if (vehicleMapper.selectById(vehicleId) == null) {
            throw new BizException(404, "车辆不存在");
        }
        VehicleCertificate cert = new VehicleCertificate();
        cert.setVehicleId(vehicleId);
        cert.setCertType(dto.certType());
        cert.setCertNo(dto.certNo());
        cert.setExpireDate(dto.expireDate());
        cert.setFileUrl(dto.fileUrl());
        certificateMapper.insert(cert);
        return ApiResult.ok(cert.getId());
    }

    @Operation(summary = "删除证件")
    @DeleteMapping("/certificates/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        if (certificateMapper.deleteById(id) == 0) {
            throw new BizException(404, "证件不存在");
        }
        return ApiResult.ok();
    }

    /**
     * 到期证件列表。
     *
     * 不传 days 时用 pricing_rule 里的 cert.warn.days（默认 30 天）。
     * 这样「提前多久预警」也是一条可配置的业务规则，不是写死在代码里的 30。
     */
    @Operation(summary = "到期证件列表")
    @GetMapping("/certificates/expiring")
    public ApiResult<List<Map<String, Object>>> expiring(
            @RequestParam(required = false) Integer days) {
        int warnDays = days != null ? days : pricingRuleService.rules().certWarnDays();
        return ApiResult.ok(certificateMapper.selectExpiring(LocalDate.now().plusDays(warnDays)));
    }
}
