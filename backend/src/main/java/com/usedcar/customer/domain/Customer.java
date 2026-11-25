package com.usedcar.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户，对应 customer 表。
 *
 * 买卖双向共用这一张表：customer_type = BUYER 只买车 / SELLER 只卖车给我们 / BOTH 两者都有。
 *
 * V1 的 DDL 给这张表既没有 deleted 列，也没有 status 列，
 * 所以这里不加 @TableLogic，也没有「启停」概念：客户一旦录入就一直保留，
 * 否则将来 sale_order.customer_id 会指向一条已经消失的记录。
 *
 * 已知限制：phone 在 V1 里只是普通索引 idx_phone，不是唯一索引，
 * 「手机号查重」目前只在 CustomerService.create 这个方法的代码里做，并发下仍可能重复。
 */
@Data
@TableName("customer")
public class Customer {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户姓名 */
    private String name;

    /** 手机号，Service 层按它查重 */
    private String phone;

    /** 身份证号，成交过户时必填，录入阶段允许为空 */
    private String idCard;

    /** BUYER 买方 / SELLER 卖方 / BOTH 双向 */
    private String customerType;

    /** NORMAL 普通 / VIP 重点 */
    private String level;

    /** 来源渠道：抖音 / 转介绍 / 门店 / 懂车帝 */
    private String source;

    private String remark;

    /** 由 DDL 的 DEFAULT CURRENT_TIMESTAMP 填，实体不主动设值 */
    private LocalDateTime createdAt;

    /** 由 DDL 的 ON UPDATE CURRENT_TIMESTAMP 填，实体不主动设值 */
    private LocalDateTime updatedAt;
}
