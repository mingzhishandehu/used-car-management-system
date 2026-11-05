package com.usedcar.vehicle.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 车辆成本明细，对应 vehicle_cost 表。
 *
 * 为什么成本要拆表而不是在 vehicle 上放几个字段：
 *   一辆车的成本是「多笔、可后补、要能追溯」的。收车款之外还有整备、运输、过户，
 *   每笔发生时间不同、凭证不同。用固定字段装，第二笔整备来了就没地方放。
 */
@Data
@TableName("vehicle_cost")
public class VehicleCost {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vehicleId;

    /** PURCHASE 收车款 / RECONDITION 整备 / TRANSPORT 运输 / TRANSFER 过户 / OTHER 其他 */
    private String costType;

    private BigDecimal amount;

    private LocalDate occurDate;

    private String remark;

    private LocalDateTime createdAt;
}
