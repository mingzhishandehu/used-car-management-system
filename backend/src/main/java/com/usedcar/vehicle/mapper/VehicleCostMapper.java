package com.usedcar.vehicle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.vehicle.domain.VehicleCost;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 车辆成本明细的数据访问。
 */
public interface VehicleCostMapper extends BaseMapper<VehicleCost> {

    /**
     * 某辆车的成本合计。
     *
     * COALESCE 兜住「一笔成本都没有」的情况：SUM 在没有行时返回 NULL，
     * 直接映射到 Java 的 BigDecimal 会抛 NullPointerException。
     * 注意返回值是 BigDecimal 而不是 double：金额用浮点会丢分，见 Vehicle 实体的注释。
     */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM vehicle_cost WHERE vehicle_id = #{vehicleId}")
    BigDecimal sumCost(@Param("vehicleId") Long vehicleId);
}
