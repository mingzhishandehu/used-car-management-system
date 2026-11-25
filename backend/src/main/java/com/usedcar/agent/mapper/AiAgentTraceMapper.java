package com.usedcar.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.agent.domain.AiAgentTrace;

/**
 * Agent 轨迹 Mapper。
 *
 * 只用到两个操作：insert（每次工具执行后落一条）、
 * selectList 按 conversation_id 升序（前端拉执行轨迹）。
 * 两种都走 BaseMapper，不需要写 XML。
 */
public interface AiAgentTraceMapper extends BaseMapper<AiAgentTrace> {
}
