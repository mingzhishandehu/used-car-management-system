-- =============================================================
-- V13：Agent 执行轨迹（只读问答每一次工具调用都留痕）
--
-- 背景：AgentQueryService 的多轮循环里，每轮选了什么工具、带了什么参数、
--       最终执行的是哪条 SQL、返回几行、花了多少毫秒、成功还是失败，
--       以前只存在于内存 List<Step> 和一行 log.info，请求结束就没了。
--       结果是：答错了只能猜；「为什么这轮没查对」无法回放；
--       也拿不出数据证明 agent 的准确率和耗时分布。
--
-- 这张表只做「记录」，不参与任何业务判断：
--   1. 写入失败不允许影响回答（Java 侧 catch 掉，只打日志）；
--   2. 不做逻辑删除，轨迹是审计数据，只增不改；
--   3. 不存模型原文，只存工具名 / 参数 / SQL / 结果摘要，避免把 prompt 塞进库。
--
-- 表数口径：15 建表 → 16 建表。这张表属于 agent 模块自用，
-- 与 vehicle / sale_order 等业务表完全隔离。
--
-- 执行方式（含中文注释，必须带字符集参数，否则注释会按 GBK 解释成乱码）：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
--       --default-character-set=utf8mb4 < V13__agent_trace.sql
-- =============================================================

USE `used_car`;

-- 表 16：Agent 工具调用轨迹。
-- 一次提问可能产生多行（多轮循环，每轮一条），round_no 是同一轮对话内的序号。
CREATE TABLE IF NOT EXISTS `ai_agent_trace`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT UNSIGNED NOT NULL COMMENT '所属 ai_conversation.id',
    `round_no`        INT             NOT NULL DEFAULT 1 COMMENT '这次提问里的第几次工具调用，从 1 开始',
    `question`        VARCHAR(500)    NOT NULL DEFAULT '' COMMENT '触发这次调用的老板原话',
    `tool`            VARCHAR(64)     NOT NULL COMMENT '工具名，固定工具名或 run_readonly_sql',
    `args_json`       VARCHAR(1000)              DEFAULT NULL COMMENT '模型给的参数 JSON，只存标量',
    `sql_text`        TEXT                       DEFAULT NULL COMMENT 'SQL 工具最终真正执行的语句（改写后的那条）',
    `result_json`     MEDIUMTEXT                 DEFAULT NULL COMMENT '工具返回结果 JSON，超过 8000 字截断后存',
    `row_count`       INT                        DEFAULT NULL COMMENT '结果行数，取自结果的 rowCount / count / total 等字段',
    `elapsed_ms`      INT                        DEFAULT NULL COMMENT '工具执行耗时（毫秒），不含模型调用时间',
    `ok`              TINYINT         NOT NULL DEFAULT 1 COMMENT '1 成功 / 0 失败',
    `error_msg`       VARCHAR(500)                 DEFAULT NULL COMMENT '失败原因，成功为 NULL',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    -- 前端按会话拉取、按 id 升序回放，这条联合索引能覆盖，不用 filesort
    KEY `idx_conv_id` (`conversation_id`, `id`),
    -- 排查「哪个工具最慢 / 失败率最高」时按工具聚合
    KEY `idx_tool_created` (`tool`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 工具调用轨迹';

-- 核对：
--   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'used_car';  期望 16
--   SHOW CREATE TABLE ai_agent_trace;
--   SELECT id, conversation_id, round_no, tool, row_count, elapsed_ms, ok
--     FROM ai_agent_trace ORDER BY id DESC LIMIT 10;
