package com.usedcar.order.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后记录，对应 after_sale 表。
 *
 * V1 的 DDL 注释里写了「清库前必须售后闭环，只要还有未完结的售后单，车辆不允许流转到 CLEARED」。
 * 这条规则以前只在注释里，接线后由 VehicleService.changeStatus 真正校验。
 */
@Data
@Accessors(chain = true)
@TableName("after_sale")
public class AfterSale {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vehicleId;

    /** 关联 sale_order.id，可空（收车后未成交也可能有售后） */
    private Long orderId;

    /** REPAIR / REFUND / COMPLAINT / OTHER */
    private String issueType;

    private String description;

    /** 售后成本，计入该车总成本 */
    private BigDecimal cost;

    /** OPEN 处理中 / CLOSED 已结案 */
    private String status;

    private Long handlerId;

    private LocalDateTime createdAt;

    private LocalDateTime closedAt;
}
