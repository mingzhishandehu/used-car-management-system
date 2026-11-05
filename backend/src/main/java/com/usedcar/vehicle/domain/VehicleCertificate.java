package com.usedcar.vehicle.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 车辆证件，对应 vehicle_certificate 表。
 *
 * 拆成独立表的原因：一辆车有多张证件，每张各有自己的到期日；
 * 保险一年一续，年检两年一次，登记证长期有效（expireDate 为空）。
 * 塞进 vehicle 表就得开 insurance_expire / inspect_expire 一堆列，加一种证件就要改表。
 */
@Data
@TableName("vehicle_certificate")
public class VehicleCertificate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vehicleId;

    /** REGISTER 登记证 / DRIVING 行驶证 / INSURANCE 交强险 / INSPECT 年检 */
    private String certType;

    private String certNo;

    /** 到期日期，为空表示长期有效 */
    private LocalDate expireDate;

    private String fileUrl;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
