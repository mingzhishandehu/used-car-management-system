package com.usedcar.alert.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存预警，对应 stock_alert 表。
 *
 * 为什么要把预警落表，而不是每次打开页面现算：
 *   现算的话「已处理 / 已忽略」这个状态无处可存，销售今天点了忽略，明天刷新又冒出来。
 *   落表之后，预警是一个有待办语义的对象，跟车辆的实时状态是两回事。
 *
 * 注意：表里有一个生成列 open_key（未处理唯一键），本实体不映射它。
 *   MyBatis-Plus 插入时只拼实体上有的字段，生成列交给数据库自己算，不会冲突。
 */
@Data
@TableName("stock_alert")
public class StockAlert {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vehicleId;

    /** SLOW_MOVING 滞销 / CERT_EXPIRE 证件到期 / LOW_MARGIN 毛利过低 */
    private String alertType;

    /** INFO / WARN / DANGER */
    private String alertLevel;

    /** 预警内容，必须带具体数字，例如「持有 82 天，建议降至 71900.00」 */
    private String content;

    /** OPEN 待处理 / IGNORED 已忽略 / RESOLVED 已处理 */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime handledAt;
}
