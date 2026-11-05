package com.usedcar.vehicle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.vehicle.domain.VehicleStatusLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 车辆状态流转日志的数据访问。
 */
public interface VehicleStatusLogMapper extends BaseMapper<VehicleStatusLog> {

    /**
     * 统计某辆车某个状态出现过几次。
     *
     * 用 @Select 直接写 SQL 而不是走 BaseMapper 的 selectCount：
     * 这里只需要一个整数，走实体映射是浪费；而且这是带业务语义的统计，SQL 写出来更直观。
     * 当前阶段没用到，阶段二做「是否已挂牌」校验时会用。
     */
    @Select("SELECT COUNT(*) FROM vehicle_status_log WHERE vehicle_id = #{vehicleId} AND to_status = #{status}")
    int countByStatus(@Param("vehicleId") Long vehicleId, @Param("status") String status);
}
