package com.usedcar.pricing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定价规则参数，对应 pricing_rule 表。
 *
 * 这张表存在的唯一目的：把业务参数从代码里赶出去。
 * 年化利率、保本加成、阶梯天数与幅度、滞销阈值，全部是「业务会改」的东西，
 * 写死在 Java 里意味着每改一次要发一次版，还会出现「线上代码和业务口径对不上」。
 */
@Data
@TableName("pricing_rule")
public class PricingRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 参数键，代码按这个 key 取 */
    private String ruleKey;

    /** 参数值，统一存字符串，怎么解析由 ruleType 决定 */
    private String ruleValue;

    /** DECIMAL 小数 / INT 整数 */
    private String ruleType;

    /** 分组：CAPITAL / BREAKEVEN / LADDER / SLOW */
    private String ruleGroup;

    private String description;

    private LocalDateTime updatedAt;
}
