package com.usedcar.vehicle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.vehicle.domain.VehicleCertificate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 车辆证件的数据访问。
 */
public interface VehicleCertificateMapper extends BaseMapper<VehicleCertificate> {

    /**
     * 查出到期日落在 [今天, 今天+dayLimit] 之间的证件，并带上车辆编号与品牌车型。
     *
     * 用 JOIN 一次查完，不在 Java 里循环查（N+1）。
     * 条件是 expire_date IS NOT NULL：长期有效的证件（登记证）不参与到期预警，
     * 不排除的话它们会永远不算"到期"，但也不能算"已到期"。
     */
    @Select("""
            SELECT c.id            AS certId,
                   c.cert_type     AS certType,
                   c.cert_no       AS certNo,
                   c.expire_date   AS expireDate,
                   v.id            AS vehicleId,
                   v.stock_no      AS stockNo,
                   v.brand         AS brand,
                   v.model         AS model
            FROM vehicle_certificate c
            JOIN vehicle v ON v.id = c.vehicle_id
            WHERE c.expire_date IS NOT NULL
              AND v.deleted = 0
              AND v.status <> 'CLEARED'
              AND c.expire_date <= #{deadline}
            ORDER BY c.expire_date ASC
            """)
    List<Map<String, Object>> selectExpiring(@Param("deadline") LocalDate deadline);
}
