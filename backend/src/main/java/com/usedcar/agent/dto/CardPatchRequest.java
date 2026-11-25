package com.usedcar.agent.dto;

import java.util.Map;

/**
 * 老板直接在卡片里改字段，或点「跳过这些直接确认」。
 *
 * fields 只会传改动过的项；值允许为 null，表示清空这一项。
 * skipOptional 为 true 时把会话标记为已跳过建议字段。
 */
public record CardPatchRequest(
        Map<String, Object> fields,
        Boolean skipOptional
) {
}
