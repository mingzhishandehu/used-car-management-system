package com.usedcar.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.agent.domain.AiMessage;

/**
 * 消息 Mapper。
 *
 * 回放走 BaseMapper.selectList + LambdaQueryWrapper 按 id 升序，
 * (conversation_id, id) 联合索引能覆盖，不用额外写 XML。
 */
public interface AiMessageMapper extends BaseMapper<AiMessage> {
}
