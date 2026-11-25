-- =============================================================
-- V9：车辆管理审批流（员工申请 → 老板批准 → 才执行业务写入）
--
-- 设计取舍：申请单独立成表，而不是让 vehicle 先进「待审批」状态。
-- 理由：待审批的车辆一旦落在 vehicle 表里，库存列表、滞销巡检、
-- 定价测算全都会把它当成真实库存，要到处加 status <> 'PENDING' 过滤，
-- 漏一处就算出错误数据。独立表让业务表只装已生效的数据。
--
-- payload_json 存的是「这次操作的参数快照」，不是对业务表的引用：
-- 员工提交后到老板批准前，原车辆可能已经被别人改过，
-- 批准时必须按当时的申请内容执行，不能现查最新值。
--
-- 执行方式（含中文注释，必须带字符集参数）：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
--       --default-character-set=utf8mb4 < V9__vehicle_approval.sql
--
-- 表数口径：V1 的 11 张 + V7 的 2 张 + 本脚本 1 张 = 14 张建表；
-- 新增的 vehicle_approval 已接线，所以 14 建表 / 12 接线 / 2 预留。
-- =============================================================

USE `used_car`;

CREATE TABLE IF NOT EXISTS `vehicle_approval`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `operation_type`    VARCHAR(30)     NOT NULL COMMENT 'CREATE_VEHICLE / AI_INTAKE_CREATE / UPDATE_VEHICLE / CHANGE_STATUS / CHANGE_PRICE / ADD_COST / ADD_CERTIFICATE / DELETE_CERTIFICATE',
    `source`            VARCHAR(20)     NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 表单 / AI 聊天收车',
    `target_vehicle_id` BIGINT UNSIGNED          DEFAULT NULL COMMENT '针对已有车辆的操作；CREATE 类为 NULL',
    `payload_json`      JSON            NOT NULL COMMENT '本次操作的参数快照，批准时按它执行',
    `applicant_id`      BIGINT UNSIGNED NOT NULL COMMENT '申请人 sys_user.id，批准后作为 created_by',
    `status`            VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审 / APPROVED 已通过 / REJECTED 已驳回 / CANCELLED 已撤回',
    `approver_id`       BIGINT UNSIGNED          DEFAULT NULL COMMENT '审批人 sys_user.id',
    `approve_remark`    VARCHAR(255)             DEFAULT NULL COMMENT '审批意见，驳回原因写这里',
    `result_vehicle_id` BIGINT UNSIGNED          DEFAULT NULL COMMENT 'CREATE 类批准后生成的 vehicle.id',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '审批时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_applicant` (`applicant_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='车辆管理申请单';

-- 车辆上冗余一个审批人，详情页能直接查到「这辆车是谁批的」，
-- 不用每次 join 申请单。ADMIN 自己直接录入时该列为 NULL（没有审批动作）。
ALTER TABLE `vehicle`
    ADD COLUMN `approved_by` BIGINT UNSIGNED NULL COMMENT '审批人 sys_user.id；ADMIN 直接录入时为 NULL' AFTER `created_by`;

-- 验证：
--   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='used_car';
--     与期望值一致；若旧库尚未执行 V6 清理两张视频历史表，会多 2
--   SHOW COLUMNS FROM vehicle LIKE 'approved_by';
