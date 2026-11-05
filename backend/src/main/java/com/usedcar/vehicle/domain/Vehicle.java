package com.usedcar.vehicle.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 车辆主表实体，对应 vehicle 表。
 *
 * 字段与列名的映射由 MyBatis-Plus 自动完成（purchase_price ↔ purchasePrice），
 * 前提是 application.yml 里开了 map-underscore-to-camel-case。
 *
 * 金额一律用 BigDecimal，不用 double。
 * 原因：double 是二进制浮点，0.1 + 0.2 != 0.3，算保本底价和毛利时会差几分钱，
 *       财务对账时这种误差会被追问。
 */
@Data
@Accessors(chain = true)
@TableName("vehicle")
public class Vehicle {

    /** 主键，AUTO 表示交给数据库自增，插入时不填 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 库存编号，业务唯一，例如 UC20261105001 */
    private String stockNo;

    /** 车架号 VIN */
    private String vin;

    /** 车牌号 */
    private String plateNo;

    /** 品牌 */
    private String brand;

    /** 车型 */
    private String model;

    /** 首次上牌日期，用来算车龄 */
    private LocalDate firstRegDate;

    /** 表显里程，单位 km */
    private Integer mileage;

    /** 收车价 */
    private BigDecimal purchasePrice;

    /** 收车日期，资金成本从这里起算 */
    private LocalDate purchaseDate;

    /** 当前挂牌价 */
    private BigDecimal listPrice;

    /** 首次挂牌价，阶梯降价的基准，调价时不改动它 */
    private BigDecimal basePrice;

    /**
     * 状态。用 String 存而不是数据库 enum 类型。
     * 原因：数据库 enum 加一个值要 ALTER TABLE，而且 Java 端和 DB 端的枚举值容易不一致；
     *       用 VARCHAR + Java 枚举转换，扩展只需改 Java。
     */
    private String status;

    /** 成交日期 */
    private LocalDate saleDate;

    /** 清库日期 */
    private LocalDate clearDate;

    /** 负责销售，关联 sys_user.id */
    private Long ownerUserId;

    /** 备注 */
    private String remark;

    /** 创建时间，由数据库默认值填充，Java 侧只读 */
    private LocalDateTime createdAt;

    /** 更新时间，由数据库 ON UPDATE 维护 */
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标记，0 未删 / 1 已删。
     * @TableLogic 让 MyBatis-Plus 在 delete 时自动改成 UPDATE deleted=1，
     * 并在所有 select 上自动追加 deleted=0，业务代码不用手写这个条件。
     */
    @TableLogic
    private Integer deleted;
}
