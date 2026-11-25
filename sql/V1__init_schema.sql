-- =============================================================
-- 二手车业务管理系统 —— 建表脚本 V1
-- 数据库：MySQL 8.4
-- 字符集：utf8mb4 / utf8mb4_0900_ai_ci（8.0 起的默认排序规则，支持 emoji）
-- 共 11 张表，分五组：
--   账号 1 张 / 客户 1 张 / 车辆主体 5 张 / 交易售后 2 张 / 预警与规则 2 张
-- 执行方式：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p < V1__init_schema.sql
-- =============================================================

CREATE DATABASE IF NOT EXISTS `used_car`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `used_car`;

-- -------------------------------------------------------------
-- 第 1 组：账号
-- -------------------------------------------------------------

-- 表 1：sys_user 员工账号
-- 设计取舍：不拆 sys_role / sys_user_role 两张表。
-- 理由：本系统只有老板、销售、财务三种固定角色，做标准 RBAC 是过度设计；
--       用一个 role 字段即可，Sa-Token 侧按角色做接口鉴权。
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`   VARCHAR(50)     NOT NULL COMMENT '登录名',
    `password`   VARCHAR(100)    NOT NULL COMMENT 'BCrypt 加密后的密文，禁止存明文',
    `real_name`  VARCHAR(50)     NOT NULL COMMENT '真实姓名',
    `phone`      VARCHAR(20)              DEFAULT NULL COMMENT '手机号',
    `role`       VARCHAR(20)     NOT NULL DEFAULT 'SALES' COMMENT '角色：ADMIN 管理员 / SALES 销售 / FINANCE 财务',
    `status`     TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='员工账号';

-- -------------------------------------------------------------
-- 第 2 组：客户
-- -------------------------------------------------------------

-- 表 2：customer 客户
-- 说明：买卖双向都用这一张表。
--   customer_type = BUYER  只买车
--   customer_type = SELLER 只卖车（我们把车收进来）
--   customer_type = BOTH   既卖给我们也买我们的车
DROP TABLE IF EXISTS `customer`;
CREATE TABLE `customer`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`          VARCHAR(50)     NOT NULL COMMENT '客户姓名',
    `phone`         VARCHAR(20)     NOT NULL COMMENT '手机号，查重主依据',
    `id_card`       VARCHAR(32)              DEFAULT NULL COMMENT '身份证号（成交过户时必填）',
    `customer_type` VARCHAR(10)     NOT NULL DEFAULT 'BUYER' COMMENT '类型：BUYER 买方 / SELLER 卖方 / BOTH 双向',
    `level`         VARCHAR(10)     NOT NULL DEFAULT 'NORMAL' COMMENT '客户等级：NORMAL 普通 / VIP 重点',
    `source`        VARCHAR(20)              DEFAULT NULL COMMENT '来源渠道：抖音 / 转介绍 / 门店 / 懂车帝',
    `remark`        VARCHAR(500)             DEFAULT NULL COMMENT '备注',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_phone` (`phone`),
    KEY `idx_name` (`name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='客户（买卖双向）';

-- -------------------------------------------------------------
-- 第 3 组：车辆主体（5 张）
-- -------------------------------------------------------------

-- 表 3：vehicle 车辆主表
-- 关键设计：
--   1. purchase_date 是资金成本的起算点，不是 created_at（录单时间可能晚几天）。
--   2. list_price 是「当前挂牌价」，每次调价写 price_change_log，主表只留最新值。
--   3. 状态机五态，流转必须经过 service 层校验，禁止直接 UPDATE。
DROP TABLE IF EXISTS `vehicle`;
CREATE TABLE `vehicle`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stock_no`          VARCHAR(32)     NOT NULL COMMENT '库存编号，例如 UC20261105001',
    `vin`               VARCHAR(32)              DEFAULT NULL COMMENT '车架号 VIN（17 位）',
    `plate_no`          VARCHAR(16)              DEFAULT NULL COMMENT '车牌号',
    `brand`             VARCHAR(50)     NOT NULL COMMENT '品牌，例如 大众',
    `model`             VARCHAR(80)     NOT NULL COMMENT '车型，例如 朗逸 2021款 1.5L 自动舒适版',
    `first_reg_date`    DATE                     DEFAULT NULL COMMENT '首次上牌日期，用于算车龄',
    `mileage`           INT                      DEFAULT NULL COMMENT '表显里程，单位 km',
    `purchase_price`    DECIMAL(12, 2)  NOT NULL COMMENT '收车价（付给卖家的钱）',
    `purchase_date`     DATE            NOT NULL COMMENT '收车日期，资金成本从这里起算',
    `list_price`        DECIMAL(12, 2)           DEFAULT NULL COMMENT '当前挂牌价（对外报价）',
    `base_price`        DECIMAL(12, 2)           DEFAULT NULL COMMENT '首次挂牌价，阶梯降价以它为基准，不随调价变化',
    `status`            VARCHAR(20)     NOT NULL DEFAULT 'PURCHASED' COMMENT '状态：PURCHASED 已收车 / ON_SALE 在售 / SOLD 已成交 / AFTER_SALE 售后 / CLEARED 已清库',
    `sale_date`         DATE                     DEFAULT NULL COMMENT '成交日期',
    `clear_date`        DATE                     DEFAULT NULL COMMENT '清库日期',
    `owner_user_id`     BIGINT UNSIGNED          DEFAULT NULL COMMENT '负责销售，关联 sys_user.id',
    `remark`            VARCHAR(500)             DEFAULT NULL COMMENT '备注',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 / 1 已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stock_no` (`stock_no`),
    KEY `idx_status` (`status`),
    KEY `idx_purchase_date` (`purchase_date`),
    KEY `idx_brand_model` (`brand`, `model`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='车辆主表';

-- 表 4：vehicle_cost 车辆成本明细
-- 为什么单独一张表：成本是「多笔、可后补」的，收车价之外还有整备、运输、过户等，
-- 用 vehicle 上的几个固定字段装不下，也无法追溯每一笔的凭证。
-- 资金成本 = 该车所有成本项之和 × 年化利率 × 持有天数 / 365，所以必须能求和。
DROP TABLE IF EXISTS `vehicle_cost`;
CREATE TABLE `vehicle_cost`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `vehicle_id`  BIGINT UNSIGNED NOT NULL COMMENT '所属车辆，关联 vehicle.id',
    `cost_type`   VARCHAR(20)     NOT NULL COMMENT '成本类型：PURCHASE 收车款 / RECONDITION 整备 / TRANSPORT 运输 / TRANSFER 过户 / OTHER 其他',
    `amount`      DECIMAL(12, 2)  NOT NULL COMMENT '金额，单位元',
    `occur_date`  DATE            NOT NULL COMMENT '发生日期',
    `remark`      VARCHAR(200)             DEFAULT NULL COMMENT '说明，例如「更换前保险杠」',
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_vehicle_id` (`vehicle_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='车辆成本明细';

-- 表 5：vehicle_certificate 车辆证件
-- 独立的理由：一辆车有多张证件，每张各有到期日，到期预警要按「最近一张」扫全库。
DROP TABLE IF EXISTS `vehicle_certificate`;
CREATE TABLE `vehicle_certificate`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `vehicle_id`   BIGINT UNSIGNED NOT NULL COMMENT '所属车辆，关联 vehicle.id',
    `cert_type`    VARCHAR(20)     NOT NULL COMMENT '证件类型：REGISTER 登记证 / DRIVING 行驶证 / INSURANCE 交强险 / INSPECT 年检',
    `cert_no`      VARCHAR(64)              DEFAULT NULL COMMENT '证件编号',
    `expire_date`  DATE                     DEFAULT NULL COMMENT '到期日期，为空表示长期有效',
    `file_url`     VARCHAR(255)             DEFAULT NULL COMMENT '扫描件地址',
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_vehicle_id` (`vehicle_id`),
    KEY `idx_expire_date` (`expire_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='车辆证件';

-- 表 6：vehicle_status_log 车辆状态流转日志
-- 作用：状态机审计。谁在什么时候把车从「在售」改成「成交」，必须有据可查，
--       否则销售误操作把车卖掉，事后无法回溯。
DROP TABLE IF EXISTS `vehicle_status_log`;
CREATE TABLE `vehicle_status_log`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `vehicle_id`  BIGINT UNSIGNED NOT NULL COMMENT '关联 vehicle.id',
    `from_status` VARCHAR(20)              DEFAULT NULL COMMENT '变更前状态，首次收车为 NULL',
    `to_status`   VARCHAR(20)     NOT NULL COMMENT '变更后状态',
    `operator_id` BIGINT UNSIGNED          DEFAULT NULL COMMENT '操作人，关联 sys_user.id',
    `remark`      VARCHAR(200)             DEFAULT NULL COMMENT '变更说明',
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    PRIMARY KEY (`id`),
    KEY `idx_vehicle_id` (`vehicle_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='车辆状态流转日志';

-- 表 7：price_change_log 调价记录
-- 作用：阶梯降价是周期性动作，必须留痕才能回答「为什么这台车降了 13%」。
DROP TABLE IF EXISTS `price_change_log`;
CREATE TABLE `price_change_log`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `vehicle_id`  BIGINT UNSIGNED NOT NULL COMMENT '关联 vehicle.id',
    `old_price`   DECIMAL(12, 2)           DEFAULT NULL COMMENT '调价前挂牌价',
    `new_price`   DECIMAL(12, 2)  NOT NULL COMMENT '调价后挂牌价',
    `reason`      VARCHAR(100)             DEFAULT NULL COMMENT '调价原因：MANUAL 手动 / LADDER 阶梯自动 / STOCK 清库',
    `operator_id` BIGINT UNSIGNED          DEFAULT NULL COMMENT '操作人，自动调价时为空',
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调价时间',
    PRIMARY KEY (`id`),
    KEY `idx_vehicle_id` (`vehicle_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='调价记录';

-- -------------------------------------------------------------
-- 第 4 组：交易与售后（2 张）
-- -------------------------------------------------------------

-- 表 8：sale_order 成交订单
-- 说明：一辆车在系统里只有一条有效订单，用 uk_vehicle_id 约束住，防重复成交。
DROP TABLE IF EXISTS `sale_order`;
CREATE TABLE `sale_order`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`       VARCHAR(32)     NOT NULL COMMENT '订单号',
    `vehicle_id`     BIGINT UNSIGNED NOT NULL COMMENT '关联 vehicle.id',
    `customer_id`    BIGINT UNSIGNED NOT NULL COMMENT '买方，关联 customer.id',
    `deal_price`     DECIMAL(12, 2)  NOT NULL COMMENT '成交价',
    `deal_date`      DATE            NOT NULL COMMENT '成交日期',
    `pay_type`       VARCHAR(20)              DEFAULT NULL COMMENT '付款方式：FULL 全款 / LOAN 按揭',
    `deposit`        DECIMAL(12, 2)  NOT NULL DEFAULT 0.00 COMMENT '定金',
    `sales_user_id`  BIGINT UNSIGNED          DEFAULT NULL COMMENT '成交销售，关联 sys_user.id',
    `status`         VARCHAR(20)     NOT NULL DEFAULT 'SIGNED' COMMENT '订单状态：SIGNED 已签约 / PAID 已收款 / DELIVERED 已交付 / CANCELED 已取消',
    `remark`         VARCHAR(500)             DEFAULT NULL COMMENT '备注',
    `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_vehicle_id` (`vehicle_id`),
    KEY `idx_customer_id` (`customer_id`),
    KEY `idx_deal_date` (`deal_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='成交订单';

-- 表 9：after_sale 售后记录
-- 说明：清库前必须售后闭环。只要还有未完结的售后单，车辆不允许流转到 CLEARED。
DROP TABLE IF EXISTS `after_sale`;
CREATE TABLE `after_sale`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `vehicle_id`    BIGINT UNSIGNED NOT NULL COMMENT '关联 vehicle.id',
    `order_id`      BIGINT UNSIGNED          DEFAULT NULL COMMENT '关联 sale_order.id',
    `issue_type`    VARCHAR(20)     NOT NULL COMMENT '问题类型：REPAIR 维修 / REFUND 退款 / COMPLAINT 投诉 / OTHER 其他',
    `description`   VARCHAR(500)    NOT NULL COMMENT '问题描述',
    `cost`          DECIMAL(12, 2)  NOT NULL DEFAULT 0.00 COMMENT '售后成本，计入该车总成本',
    `status`        VARCHAR(20)     NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN 处理中 / CLOSED 已结案',
    `handler_id`    BIGINT UNSIGNED          DEFAULT NULL COMMENT '处理人，关联 sys_user.id',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `closed_at`     DATETIME                 DEFAULT NULL COMMENT '结案时间',
    PRIMARY KEY (`id`),
    KEY `idx_vehicle_id` (`vehicle_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='售后记录';

-- -------------------------------------------------------------
-- 第 5 组：预警与规则（2 张）
-- -------------------------------------------------------------

-- 表 10：stock_alert 库存预警
-- 作用：把「每天巡检算出来的异常」落成待办，销售登录就能看到该处理哪台车。
-- 用 uk_vehicle_alert 保证同一台车同类预警在未处理前不重复插入。
DROP TABLE IF EXISTS `stock_alert`;
CREATE TABLE `stock_alert`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `vehicle_id`   BIGINT UNSIGNED NOT NULL COMMENT '关联 vehicle.id',
    `alert_type`   VARCHAR(20)     NOT NULL COMMENT '预警类型：SLOW_MOVING 滞销 / CERT_EXPIRE 证件到期 / LOW_MARGIN 毛利过低',
    `alert_level`  VARCHAR(10)     NOT NULL COMMENT '级别：INFO 提示 / WARN 警告 / DANGER 严重',
    `content`      VARCHAR(300)    NOT NULL COMMENT '预警内容，含具体数字',
    `status`       VARCHAR(20)     NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN 待处理 / IGNORED 已忽略 / RESOLVED 已处理',
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '产生时间',
    `handled_at`   DATETIME                 DEFAULT NULL COMMENT '处理时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_vehicle_alert` (`vehicle_id`, `alert_type`, `status`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='库存预警';

-- 表 11：pricing_rule 定价规则参数
-- 这张表是「规则引擎」四个字的落脚点：
--   代码里不写 8%、5%、2000、45/60/75 这些魔法数字，全部从这张表读。
--   改规则 = UPDATE 一行数据，不改代码、不重新发版。
--   表里放 key-value + 类型 + 说明，新增规则只要插数据。
DROP TABLE IF EXISTS `pricing_rule`;
CREATE TABLE `pricing_rule`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `rule_key`     VARCHAR(50)     NOT NULL COMMENT '参数键，代码按这个 key 取',
    `rule_value`   VARCHAR(50)     NOT NULL COMMENT '参数值，统一存字符串，由 rule_type 决定怎么解析',
    `rule_type`    VARCHAR(10)     NOT NULL DEFAULT 'DECIMAL' COMMENT '值类型：DECIMAL 小数 / INT 整数',
    `rule_group`   VARCHAR(30)     NOT NULL COMMENT '分组：CAPITAL 资金成本 / BREAKEVEN 保本 / LADDER 阶梯降价 / SLOW 滞销',
    `description`  VARCHAR(200)             DEFAULT NULL COMMENT '参数含义说明',
    `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_key` (`rule_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='定价规则参数';

-- -------------------------------------------------------------
-- 初始数据：pricing_rule 的 14 个参数
-- 数值来源：简历里写的业务口径，落到这里就有唯一出处。
-- -------------------------------------------------------------
INSERT INTO `pricing_rule` (`rule_key`, `rule_value`, `rule_type`, `rule_group`, `description`)
VALUES ('capital.annual.rate', '0.08', 'DECIMAL', 'CAPITAL', '资金成本年化利率 8%'),
       ('capital.days.base', '365', 'INT', 'CAPITAL', '资金成本按 365 天折算日息'),
       ('breakeven.markup', '0.05', 'DECIMAL', 'BREAKEVEN', '保本底价 = 总成本 × (1 + 5%)'),
       ('breakeven.fixed', '2000', 'DECIMAL', 'BREAKEVEN', '保本底价额外固定加成 2000 元'),
       ('ladder.tier1.days', '45', 'INT', 'LADDER', '第一档降价触发持有天数'),
       ('ladder.tier1.rate', '0.03', 'DECIMAL', 'LADDER', '第一档降价幅度 3%'),
       ('ladder.tier2.days', '60', 'INT', 'LADDER', '第二档降价触发持有天数'),
       ('ladder.tier2.rate', '0.08', 'DECIMAL', 'LADDER', '第二档降价幅度 8%'),
       ('ladder.tier3.days', '75', 'INT', 'LADDER', '第三档降价触发持有天数'),
       ('ladder.tier3.rate', '0.13', 'DECIMAL', 'LADDER', '第三档降价幅度 13%'),
       ('slow.attention.days', '45', 'INT', 'SLOW', '持有超过 45 天进入滞销关注'),
       ('slow.warn.days', '60', 'INT', 'SLOW', '持有超过 60 天判为滞销'),
       ('slow.danger.days', '75', 'INT', 'SLOW', '持有超过 75 天判为严重滞销'),
       ('cert.warn.days', '30', 'INT', 'SLOW', '证件到期前 30 天开始预警');
