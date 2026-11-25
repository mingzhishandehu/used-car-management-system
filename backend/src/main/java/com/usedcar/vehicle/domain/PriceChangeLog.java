package com.usedcar.vehicle.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 调价流水，对应 price_change_log 表。 */
@Data
@TableName("price_change_log")
public class PriceChangeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vehicleId;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private String reason;
    private Long operatorId;
    private LocalDateTime createdAt;
}
