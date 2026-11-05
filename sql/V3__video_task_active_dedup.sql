-- =============================================================
-- V3：修正 video_task 的去重约束
--
-- 问题：V1 里是 UNIQUE KEY uk_dedup (dedup_key)，这是一条绝对约束——
--   同一辆车 + 同一版文案，只要提交过一次，以后永远不能再提交，哪怕上一次已经 FAILED。
--   任务失败后想重试，会直接撞唯一键，重试入口形同虚设。
--
-- 正确语义是「同一时刻只允许一个进行中的任务」，历史任务不该占用去重位。
-- 解法同 V2：生成列 + 唯一索引，非进行中时为 NULL，利用 NULL 可重复。
-- =============================================================

USE `used_car`;

ALTER TABLE `video_task`
    DROP INDEX `uk_dedup`,
    ADD COLUMN `active_dedup` VARCHAR(80)
        GENERATED ALWAYS AS (
            IF(`status` IN ('PENDING', 'SUBMITTED', 'PROCESSING', 'DOWNLOADING', 'COMPOSING'),
               `dedup_key`, NULL)
        ) STORED COMMENT '进行中任务的去重键，终态时为 NULL，允许失败后重新提交',
    ADD UNIQUE KEY `uk_active_dedup` (`active_dedup`);
