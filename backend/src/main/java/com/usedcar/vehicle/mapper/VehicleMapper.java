package com.usedcar.vehicle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.vehicle.domain.Vehicle;

/**
 * 车辆数据访问。
 *
 * BaseMapper 已经提供了 insert / deleteById / updateById / selectById / selectPage 等方法，
 * 单表操作不需要写一行 SQL，也不需要 XML 文件。
 *
 * 复杂查询（例如仪表盘的多表聚合）再在本接口里加 @Select 或 XML，不要提前写。
 */
public interface VehicleMapper extends BaseMapper<Vehicle> {
}
