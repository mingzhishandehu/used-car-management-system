package com.usedcar.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.agent.domain.AiConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 会话 Mapper。
 *
 * 只有一条自定义 SQL：确认时的 CAS（Compare-And-Set）。
 * 为什么不先 SELECT 再 UPDATE？两个并发请求都会读到 DRAFT，然后都去插车。
 * 把条件写进 UPDATE 的 WHERE，InnoDB 会对这一行加排他锁，
 * 第二个请求等第一个提交后重新判断条件，影响行数变成 0，幂等就成立了。
 */
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    @Update("UPDATE ai_conversation SET card_status = 'CONFIRMED' " +
            "WHERE id = #{id} AND card_status = 'DRAFT'")
    int casConfirm(@Param("id") Long id);

    /** 员工点确认时把卡片置为「已提交申请」，同样用 CAS 防连点重复建申请单。 */
    @Update("UPDATE ai_conversation SET card_status = 'PENDING' " +
            "WHERE id = #{id} AND card_status = 'DRAFT'")
    int casPending(@Param("id") Long id);
}
