package com.usedcar.vehicle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.vehicle.domain.VehicleHandover;

/**
 * 流转记录 Mapper。放在 vehicle.mapper 包，已在 @MapperScan 的显式列表里，不用改启动类。
 * 只有 insert 和按车辆查询，不提供 update / delete：留痕记录一旦写入就不该被改。
 */
public interface VehicleHandoverMapper extends BaseMapper<VehicleHandover> {
}
