-- =============================================================
-- V5：给 customer.phone 加唯一约束，兜住「先查后插」的并发竞态
--
-- 背景（REVIEW 清单 R45）：
--   CustomerService.create 这个方法是「先 SELECT COUNT(*) 查重，再 INSERT」。
--   在 MySQL 默认 REPEATABLE READ 下，普通 SELECT 是不加锁的 MVCC 快照读，
--   两个并发请求都查到 0 条时，两个 INSERT 都会成功，库里出现同号客户。
--   Java 层的查重只能给友好提示，真正守住必须靠数据库唯一键。
--
-- 执行前必须先确认无重复数据，否则 ADD UNIQUE KEY 会失败：
--   SELECT phone, COUNT(*) FROM customer GROUP BY phone HAVING COUNT(*) > 1;
--   本脚本执行前实测：0 条重复，共 8 条客户。
--
-- 执行方式（含中文注释，必须带字符集参数）：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uusedcar_app -p \
--       --default-character-set=utf8mb4 < V5__customer_phone_unique.sql
--
-- DROP + ADD 写在同一条 ALTER 里：MySQL 8.0 起 DDL 原子，
-- 中间不会短暂出现「既没普通索引也没唯一索引」的状态。
-- =============================================================

USE `used_car`;

ALTER TABLE `customer`
    DROP INDEX `idx_phone`,
    ADD UNIQUE KEY `uk_phone` (`phone`);

-- 验证：
--   SELECT INDEX_NAME, NON_UNIQUE FROM information_schema.statistics
--    WHERE table_schema='used_car' AND table_name='customer' AND index_name='uk_phone';
--   期望 NON_UNIQUE = 0
