package com.usedcar.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.order.domain.SaleOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 成交订单数据访问。 */
public interface SaleOrderMapper extends BaseMapper<SaleOrder> {

    /** 取当天已用的最大订单号，用于生成 SO + yyyyMMdd + 三位流水。 */
    @Select("SELECT MAX(order_no) FROM sale_order WHERE order_no LIKE CONCAT(#{prefix}, '%')")
    String maxOrderNo(@Param("prefix") String prefix);
}
