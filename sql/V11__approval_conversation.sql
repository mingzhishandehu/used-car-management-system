-- =============================================================
-- V11：把 AI 收车申请单和它所属的会话关联起来
--
-- 起因：员工用 AI 收车点确认 → 生成 AI_INTAKE_CREATE 申请单、卡片置 PENDING；
--       老板在审批中心点通过后，车辆入库了，但会话页面还一直显示「等待审批」。
--       原因是申请单没记「这单来自哪个会话」，审批模块没法回写。
--
-- 只加一列，不新建表，表数口径仍是 15 建表 / 13 接线 / 2 预留。
-- 只有 AI_INTAKE_CREATE 类型的申请单这一列有值，其余类型保持 NULL。
--
-- 执行方式（含中文注释，必须带字符集参数）：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
--       --default-character-set=utf8mb4 < V11__approval_conversation.sql
-- =============================================================

USE `used_car`;

ALTER TABLE `vehicle_approval`
    ADD COLUMN `source_conversation_id` BIGINT UNSIGNED NULL
        COMMENT 'AI 收车会话 id，仅 AI_INTAKE_CREATE 有值；批准后回写该会话的卡片状态' AFTER `result_vehicle_id`,
    ADD INDEX `idx_source_conversation` (`source_conversation_id`);

-- 验证：SHOW COLUMNS FROM vehicle_approval LIKE 'source_conversation_id';
