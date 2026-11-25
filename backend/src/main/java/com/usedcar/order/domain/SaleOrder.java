package com.usedcar.order.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 成交订单，对应 sale_order 表。
 *
 * uk_vehicle_id 保证一辆车只能成交一次；订单由「成交登记」接口写入，
 * 不再由状态流转接口顺手插，避免「点了成交但没填客户和成交价」的脏订单。
 * 这张表没有 deleted 列，不加 @TableLogic。
 */
@Data
@TableName("sale_order")
public class SaleOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long vehicleId;

    /** 买方，关联 customer.id */
    private Long customerId;

    private BigDecimal dealPrice;

    private LocalDate dealDate;

    /** FULL 全款 / LOAN 按揭 */
    private String payType;

    private BigDecimal deposit;

    /** 成交销售，默认取车辆对接人 */
    private Long salesUserId;

    /** SIGNED / PAID / DELIVERED / CANCELED */
    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
