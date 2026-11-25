package com.usedcar.agent.dto;

/**
 * 给前端渲染卡片用的字段规格，来源只有 IntakeFieldSpec 这一处。
 * group 与 type 用字符串而不是枚举，前端拿到的就是 "REQUIRED" / "MONEY" 这类值，
 * 不用在前端再复制一份枚举定义。
 */
public record FieldSpecDTO(
        String key,
        String label,
        String group,
        String type,
        String hint
) {
}
