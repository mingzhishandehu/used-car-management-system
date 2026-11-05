-- =============================================================
-- V2：修正 stock_alert 的「未处理预警不可重复」约束
--
-- 问题：V1 里写的是 UNIQUE KEY (vehicle_id, alert_type, status)。
--   它能挡住「同一台车同类预警出现两条 OPEN」，但挡不住反方向的问题：
--   同一台车的同类预警，第二次被处理成 RESOLVED 时，会和历史上第一条 RESOLVED 撞唯一键，
--   于是「第二次滞销预警」永远写不进去，巡检任务直接抛 DuplicateKeyException。
--
-- 解法：MySQL 没有部分索引，但唯一索引里 NULL 可以重复，这是标准绕法。
--   加一个生成列 open_key，只有 status='OPEN' 时才产生值，其余为 NULL。
--   对 open_key 建唯一索引，效果等价于「只对未处理的记录做唯一约束」。
-- =============================================================

USE `used_car`;

ALTER TABLE `stock_alert`
    DROP INDEX `uk_vehicle_alert`,
    ADD COLUMN `open_key` VARCHAR(80)
        GENERATED ALWAYS AS (
            IF(`status` = 'OPEN', CONCAT(`vehicle_id`, ':', `alert_type`), NULL)
        ) STORED COMMENT '未处理预警的唯一键，非 OPEN 时为 NULL，利用 NULL 可重复实现部分唯一',
    ADD UNIQUE KEY `uk_open_alert` (`open_key`);
