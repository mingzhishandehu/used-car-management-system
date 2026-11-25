package com.usedcar.vehicle.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 车辆对接权流转记录，对应 vehicle_handover 表。只追加，不删不改。
 *
 * 为什么必须有这张表：把 vehicle.owner_user_id 直接 UPDATE 成新人，
 * 「这辆车原来是离职员工对接的」这条事实就永久消失了。
 * 一辆车还可能被流转两次，所以存的是链上的每一跳，不是只存「上一任」。
 */
@Data
@Accessors(chain = true)
@TableName("vehicle_handover")
public class VehicleHandover {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vehicleId;

    private Long fromUserId;

    private Long toUserId;

    /** 办理这次离职交接的管理员 */
    private Long operatorId;

    private String reason;

    private LocalDateTime createdAt;
}
