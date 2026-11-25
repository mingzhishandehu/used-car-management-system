package com.usedcar.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * Agent 工具调用轨迹，对应 ai_agent_trace 表。只增不改，不做逻辑删除。
 *
 * 为什么要这张表：多轮循环里「模型选了哪个工具、带了什么参数、最后执行的 SQL 是什么、
 * 返回几行、花了多久、成功还是失败」，以前只活在内存 List&lt;Step&gt; 和一行 log.info 里，
 * 请求一结束就没了。答错了没法回放，也拿不出准确率和耗时分布的证据。
 *
 * 这张表不参与任何业务判断，写入失败只打日志，绝不允许影响回答本身。
 */
@Data
@Accessors(chain = true)
@TableName("ai_agent_trace")
public class AiAgentTrace {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ai_conversation.id */
    private Long conversationId;

    /** 这次提问里的第几次工具调用，从 1 开始；一次提问多轮会有多条 */
    private Integer roundNo;

    /** 触发这次调用的老板原话，落库是为了脱离会话也能看懂这条轨迹 */
    private String question;

    /** 工具名：固定工具名，或 run_readonly_sql */
    private String tool;

    /** 模型给的参数 JSON，只保留标量 */
    private String argsJson;

    /** SQL 工具最终真正执行的语句（可能是改写后的那条） */
    private String sqlText;

    /** 工具返回结果 JSON，超过上限截断 */
    private String resultJson;

    /** 结果行数，取自结果里的 rowCount / count / total 等字段 */
    private Integer rowCount;

    /** 工具执行耗时（毫秒），不含模型调用时间 */
    private Integer elapsedMs;

    /** 1 成功 / 0 失败，对齐表里的 TINYINT */
    private Integer ok;

    private String errorMsg;

    private LocalDateTime createdAt;
}
