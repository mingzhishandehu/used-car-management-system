package com.usedcar.vehicle.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 车辆状态流转日志，对应 vehicle_status_log 表。
 *
 * 写入时机只有一个：VehicleService 里每次状态流转成功后，紧跟着插一条。
 * 不允许在别的地方补写——补写意味着时间点和操作人可能是猜的，审计就失去意义。
 */
@Data
@TableName("vehicle_status_log")
public class VehicleStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vehicleId;

    /** 变更前状态，首次收车时为空 */
    private String fromStatus;

    /** 变更后状态 */
    private String toStatus;

    /** 操作人 sys_user.id */
    private Long operatorId;

    private String remark;

    private LocalDateTime createdAt;
}
