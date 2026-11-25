-- =============================================================
-- V10：员工离职 + 未完成车辆对接权流转
--
-- 两件事分开存，别混：
--   1. sys_user.employment_status  → 人事状态 ACTIVE 在职 / LEFT 离职。
--      它和已有的 status（1 启用 / 0 停用，表达能不能登录）不是一个维度：
--      「临时停用的在职员工」不能被当成离职。办理离职时两个字段一起改。
--   2. vehicle_handover            → 每一次对接权流转的完整留痕。
--      直接 UPDATE vehicle.owner_user_id 会把「原来是谁对接的」抹掉，
--      用户明确要求以前记录不能删，所以旧归属必须另存一行，
--      而且一辆车可能被流转两次（A 离职→B，B 又离职→C），要存得下整条链。
--
-- 「未完成订单」口径：本项目没有 sale_order 的业务实现（该表是预留的），
-- 实际对应「owner_user_id = 离职员工 且 status <> 'CLEARED' 的车辆」。
-- CLEARED 已清库视为完成，不参与流转。
--
-- 不删任何东西：vehicle.created_by（谁录入的）、vehicle_status_log（状态流水）、
-- price_change_log、vehicle_approval、ai_conversation / ai_message 全部原样保留。
--
-- 执行方式（含中文注释，必须带字符集参数）：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
--       --default-character-set=utf8mb4 < V10__employee_handover.sql
--
-- 表数口径：V1 的 11 + V7 的 2 + V9 的 1 + 本脚本 1 = 15 张建表；
-- 新增的 vehicle_handover 已接线，所以 15 建表 / 13 接线 / 2 预留。
-- =============================================================

USE `used_car`;

ALTER TABLE `sys_user`
    ADD COLUMN `employment_status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '在职状态：ACTIVE 在任 / LEFT 离职（与 status 登录开关相互独立）' AFTER `status`;

CREATE TABLE IF NOT EXISTS `vehicle_handover`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `vehicle_id`   BIGINT UNSIGNED NOT NULL COMMENT '被流转的车辆',
    `from_user_id` BIGINT UNSIGNED NOT NULL COMMENT '原对接人 sys_user.id',
    `to_user_id`   BIGINT UNSIGNED NOT NULL COMMENT '新对接人 sys_user.id',
    `operator_id`  BIGINT UNSIGNED NOT NULL COMMENT '办理离职的管理员 sys_user.id',
    `reason`       VARCHAR(255)             DEFAULT NULL COMMENT '流转原因，例如「XX 离职交接」',
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '流转时间',
    PRIMARY KEY (`id`),
    KEY `idx_vehicle_id` (`vehicle_id`, `id`),
    KEY `idx_from_user` (`from_user_id`),
    KEY `idx_to_user` (`to_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='车辆对接权流转记录（只追加，不删除）';

-- 验证：
--   SHOW COLUMNS FROM sys_user LIKE 'employment_status';   期望默认 ACTIVE
--   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='used_car';
--     新库期望 15；若旧库尚未执行 V6 清理两张视频历史表，会多 2（本机已清理）
