-- =============================================================
-- V12：AI 会话摘要压缩
--
-- 背景：原来 loadHistory 硬截最近 12 条（usedcar.ai.max-history），
-- 超长会话的早期上下文直接丢失，老板说「刚才那辆大众」AI 完全不知道指哪辆。
-- 现在把超出的部分压成一段摘要存起来，loadHistory 拼成「摘要 + 最近 N 条」。
--
-- 表数口径不变：只给 ai_conversation 加两列，不新建表，
-- 仍然是 15 建表 / 15 接线 / 0 预留。
--
-- 执行方式（含中文注释，必须带字符集参数）：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
--       --default-character-set=utf8mb4 < V12__ai_summary.sql
-- =============================================================

USE `used_car`;

ALTER TABLE `ai_conversation`
    ADD COLUMN `summary_text`      TEXT            NULL              COMMENT '更早对话的压缩摘要，拼在最近 N 条原始消息之前进模型上下文' AFTER `vehicle_id`,
    ADD COLUMN `summarized_msg_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已被摘要覆盖的最后一条 ai_message.id，只压缩它之后的' AFTER `summary_text`;

-- 核对
--   SHOW CREATE TABLE ai_conversation;  确认 summary_text / summarized_msg_id 都在
--   SELECT id, title, summarized_msg_id, CHAR_LENGTH(summary_text) FROM ai_conversation ORDER BY id DESC LIMIT 10;
