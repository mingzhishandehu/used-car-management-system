package com.usedcar.vehicle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.vehicle.domain.VehicleCost;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    /**
     * 批量取多辆车的成本合计，返回 vehicleId → totalCost。
     *
     * 用途：每日巡检要算几百台车，逐台调 sumCost 就是 N+1（几百次往返）。
     * 一次 GROUP BY 查完，把结果塞进 Map 再逐台算，数据库只往返一次。
     *
     * <script> 是 MyBatis 的注解式动态 SQL 写法，等价于 XML 里的 <foreach>。
     * 空集合会拼出 IN () 导致语法错误，所以调用方必须保证入参非空。
     */
    @Select("""
            <script>
            SELECT vehicle_id AS vehicleId, COALESCE(SUM(amount), 0) AS total
            FROM vehicle_cost
            WHERE vehicle_id IN
            <foreach collection="vehicleIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY vehicle_id
            </script>
            """)
    List<Map<String, Object>> sumCostByVehicleIds(@Param("vehicleIds") List<Long> vehicleIds);
}
