-- =============================================================
-- V8：员工模块 —— 给 vehicle 补「录入人」归属字段
--
-- 背景：需求要求每辆车都要能查到「哪位员工增加/对接的」，老板自己的
--       admin 账号录入时也必须记，不搞特殊。
--
-- 两个字段的分工，别混：
--   created_by    → 谁把这条记录录进系统的。只从登录态取，不接受前端传，
--                   否则谁都能把车记到别人头上（和状态流水 operator_id 同一原则）。
--   owner_user_id → 谁对接/负责这辆车。V1 里已有这个字段，只是一直没被赋值。
--                   允许在收车表单里指定；不指定时后端默认取当前登录人。
--
-- 历史数据不回填。V8 执行前已存在的车辆 created_by 为 NULL，表示「本次升级前录入，
-- 当时没有记录归属」。凭猜测把老数据全算到 admin 头上是伪造记录，不这么做。
-- 确实要回填时，由项目负责人确认后手工执行：
--   UPDATE vehicle SET created_by = 1 WHERE created_by IS NULL;
--
-- 执行方式（含中文注释，必须带字符集参数）：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
--       --default-character-set=utf8mb4 < V8__employee_module.sql
--
-- 本脚本只加列和索引，不新建表，表数口径仍是 13 张建表 / 11 张接线 / 2 张预留。
-- =============================================================

USE `used_car`;

ALTER TABLE `vehicle`
    ADD COLUMN `created_by` BIGINT UNSIGNED NULL COMMENT '录入人 sys_user.id，从登录态取，不接受前端传' AFTER `owner_user_id`,
    ADD INDEX `idx_created_by` (`created_by`);

-- 验证：
--   SHOW COLUMNS FROM vehicle LIKE 'created_by';   期望 Null=YES, Default=NULL
--   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='used_car';
--     若含两张视频历史表会多 2；本机已于 2026-11-09 执行 V6 清理，当前与期望值一致
