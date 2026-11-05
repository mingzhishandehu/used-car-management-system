package com.usedcar.vehicle.dto;

import com.usedcar.vehicle.domain.Vehicle;
import com.usedcar.vehicle.domain.VehicleStatusLog;

import java.math.BigDecimal;
import java.util.List;

/**
 * 车辆详情。
 *
 * 三个字段拼成一个响应，而不是让前端发三次请求：
 *   车辆基本信息 + 成本合计 + 状态流水
 * 这三样在「车辆详情页」是同一屏展示的，拆成三个接口只会多两次往返。
 *
 * 用 record 而不是 Map：字段名固定、类型明确，Swagger 能直接渲染出结构。
 */
public record VehicleDetailVO(

        /** 车辆主体 */
        Vehicle vehicle,

        /** 成本合计（含收车款），阶段二会在此基础上算资金成本和保本底价 */
        BigDecimal totalCost,

        /** 状态流水，按时间正序 */
        List<VehicleStatusLog> statusLogs
) {
}
