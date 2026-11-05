package com.usedcar.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.alert.domain.StockAlert;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 库存预警的数据访问。
 */
public interface StockAlertMapper extends BaseMapper<StockAlert> {

    /**
     * 待处理预警列表，带车辆编号与车型。
     *
     * 排序规则：级别优先（DANGER 在最前），同级按产生时间正序（老预警先处理）。
     * MySQL 里用 FIELD() 做自定义排序，比在 Java 里 sort 更省内存。
     */
    @Select("""
            SELECT a.id, a.vehicle_id AS vehicleId, a.alert_type AS alertType,
                   a.alert_level AS alertLevel, a.content, a.status, a.created_at AS createdAt,
                   v.stock_no AS stockNo, v.brand, v.model
            FROM stock_alert a
            JOIN vehicle v ON v.id = a.vehicle_id
            WHERE a.status = 'OPEN'
            ORDER BY FIELD(a.alert_level, 'DANGER', 'WARN', 'INFO'), a.created_at ASC
            """)
    List<Map<String, Object>> selectOpenWithVehicle();
}
