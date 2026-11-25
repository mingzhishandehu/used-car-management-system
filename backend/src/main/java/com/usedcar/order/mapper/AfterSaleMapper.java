package com.usedcar.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.order.domain.AfterSale;

/** 售后记录数据访问。清库校验只需要按 vehicleId + status 计数。 */
public interface AfterSaleMapper extends BaseMapper<AfterSale> {
}
