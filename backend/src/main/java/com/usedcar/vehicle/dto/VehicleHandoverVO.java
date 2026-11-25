package com.usedcar.vehicle.dto;

import java.time.LocalDateTime;

/**
 * 车辆详情页的流转记录出参。姓名在这里带出，前端不用再按 id 发请求。
 */
public record VehicleHandoverVO(
        Long id,
        Long vehicleId,
        Long fromUserId,
        String fromUserName,
        Long toUserId,
        String toUserName,
        String operatorName,
        String reason,
        LocalDateTime createdAt
) {
}
