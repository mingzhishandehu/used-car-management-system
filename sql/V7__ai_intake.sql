-- =============================================================
-- V7：AI 收车会话与消息（长期保存）
--
-- 背景：老板用自然语言收车，AI 只负责「抽取参数」，不写业务表。
--       卡片草稿、对话历史要长期留存：万一车辆信息对不上，
--       可以翻回当时的聊天记录核对老板原话。
--
-- 两张表都是 Agent 自己的存储，跟 vehicle / vehicle_cost /
-- vehicle_status_log 三张业务表完全隔离。Agent 模块只 Mapper 这两张表，
-- 业务写入一律经 VehicleService.create() 这个方法。
--
-- 执行方式（含中文注释，必须带字符集参数，否则注释和中文数据会按 GBK 解释）：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p \
--       --default-character-set=utf8mb4 < V7__ai_intake.sql
--
-- 执行后表数口径：V1 的 11 张 + 本脚本 2 张 = 13 张建表。
-- =============================================================

USE `used_car`;

-- 表 12：AI 收车会话。一个会话对应一辆待录入的车、一张卡片。
CREATE TABLE IF NOT EXISTS `ai_conversation`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`         VARCHAR(100)    NOT NULL DEFAULT '新的收车会话' COMMENT '会话标题，取首句用户输入截断',
    `owner_user_id` BIGINT UNSIGNED NOT NULL COMMENT '发起人 sys_user.id，会话只能本人读写',
    `draft_json`    JSON                     DEFAULT NULL COMMENT '当前卡片草稿：字段名 -> 值',
    `card_status`   VARCHAR(20)     NOT NULL DEFAULT 'NONE' COMMENT 'NONE 无卡片 / DRAFT 可编辑 / CONFIRMED 已确认(灰) / DISCARDED 已放弃',
    `skip_optional` TINYINT         NOT NULL DEFAULT 0 COMMENT '1=老板主动跳过了建议字段，允许直接确认',
    `vehicle_id`    BIGINT UNSIGNED          DEFAULT NULL COMMENT '确认录入后生成的 vehicle.id',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_owner_updated` (`owner_user_id`, `updated_at`),
    -- 一辆车只能由一个会话确认产出。配合 Java 侧 card_status 的 CAS 更新，
    -- 双保险防老板连点两次「确认录入」插出两辆车。
    UNIQUE KEY `uk_conv_vehicle` (`vehicle_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 收车会话';

-- 表 13：AI 收车消息。card_json 是「发这条消息时卡片长什么样」的快照，
-- 不是外键引用当前草稿。快照必须存：卡片后来被改过、确认过，
-- 历史回放仍要能还原当时那一版，否则聊天记录就对不上了。
CREATE TABLE IF NOT EXISTS `ai_message`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT UNSIGNED NOT NULL COMMENT '所属会话',
    `role`            VARCHAR(20)     NOT NULL COMMENT 'USER 老板 / ASSISTANT AI',
    `content`         TEXT            NOT NULL COMMENT '消息正文，纯文本，不渲染 markdown',
    `card_json`       JSON                     DEFAULT NULL COMMENT '该消息附带的卡片快照，NULL 表示这条消息不带卡片',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    -- 按会话顺序回放，走 (conversation_id, id) 联合索引，不用 filesort
    KEY `idx_conv_id` (`conversation_id`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 收车消息';

-- 验证：
--   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='used_car';  期望 13
--   SHOW CREATE TABLE ai_conversation;  确认 card_status / uk_conv_vehicle 都在
