package com.usedcar.vehicle.service;

import com.usedcar.common.BizException;
import com.usedcar.vehicle.domain.VehicleCertificate;
import com.usedcar.vehicle.dto.VehicleCertificateDTO;
import com.usedcar.vehicle.mapper.VehicleCertificateMapper;
import com.usedcar.vehicle.mapper.VehicleMapper;
import org.springframework.stereotype.Service;

/**
 * 车辆证件写入。
 *
 * 单独抽一层是为了让「HTTP 直接写」和「审批通过后写」走同一份逻辑：
 * 之前 CertificateController 直接注入 Mapper，审批执行方如果也注入 Mapper，
 * 就变成两处各写一遍 insert，字段漏一个都查不出来。
 * 现在两边都只认这个类，审批层不需要碰任何业务 Mapper。
 */
@Service
public class CertificateService {

    private final VehicleCertificateMapper certificateMapper;
    private final VehicleMapper vehicleMapper;

    public CertificateService(VehicleCertificateMapper certificateMapper,
                              VehicleMapper vehicleMapper) {
        this.certificateMapper = certificateMapper;
        this.vehicleMapper = vehicleMapper;
    }

    public Long create(Long vehicleId, VehicleCertificateDTO dto) {
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
        return cert.getId();
    }

    public void delete(Long id) {
        if (certificateMapper.deleteById(id) == 0) {
            throw new BizException(404, "证件不存在");
        }
    }
}
