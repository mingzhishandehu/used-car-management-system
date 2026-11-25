package com.usedcar.agent.dto;

import java.time.LocalDateTime;

/**
 * 执行轨迹的对外视图，给前端「AI 这轮做了什么」面板用。
 *
 * 不复用实体 AiAgentTrace：实体里有 resultJson 全量，一次可能 8KB，
 * 一屏几十条会把响应撑大。这里只给截断后的摘要，前端展开也够看。
 */
public record TraceVO(
        Long id,
        Integer roundNo,
        String question,
        String tool,
        String args,
        String sql,
        Integer rowCount,
        Integer elapsedMs,
        boolean ok,
        String error,
        String result,
        LocalDateTime createdAt
) {
}
