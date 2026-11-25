-- =============================================================
-- V4：客户模块种子数据（8 条虚拟客户）
--
-- 目的：给刚接线的 /api/customers 提供可直接演示的数据，
--       覆盖 BUYER / SELLER / BOTH 三种类型、NORMAL / VIP 两种等级、
--       抖音 / 转介绍 / 门店 / 懂车帝 四种来源。
--
-- 执行方式（手工，本项目还没接 Flyway，脚本不会自动跑）：
--   "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uusedcar_app -p \
--       --default-character-set=utf8mb4 < V4__seed_customers.sql
--
-- 【必须带 --default-character-set=utf8mb4】
--   Windows 下的 mysql.exe 默认按 GBK 解释输入文件，而本脚本是 UTF-8，
--   不加这个参数会把「周涛」写成「鍛ㄦ稕」这类乱码，且库里看起来「有数据」很难发现。
--   实测踩过一次，接口返回中文乱码才暴露。
--
-- 幂等：固定 id 1~8 + ON DUPLICATE KEY UPDATE。
--   重跑既不会插出重复客户，也能把已写错的中文/字段值修正回来（INSERT IGNORE 做不到后者）。
-- 注意：phone 在 V1 里只是普通索引 idx_phone，不是唯一索引，
--       真正防重目前靠 CustomerService.create 这个方法的查重逻辑。
-- 数据均为虚构：手机号用 1380000000x 段，身份证号留空（过户时才补录）。
-- =============================================================

USE `used_car`;

INSERT INTO `customer`
    (`id`, `name`, `phone`, `id_card`, `customer_type`, `level`, `source`, `remark`)
VALUES
    (1, '张伟',   '13800000001', NULL, 'BUYER',  'NORMAL', '抖音',   '意向紧凑型轿车，预算 8 万'),
    (2, '李娜',   '13800000002', NULL, 'BUYER',  'VIP',    '转介绍', '老客户推荐，预算 15 万'),
    (3, '王强',   '13800000003', NULL, 'BUYER',  'NORMAL', '懂车帝', '关注新能源二手车'),
    (4, '刘敏',   '13800000004', NULL, 'SELLER', 'NORMAL', '门店',   '有一台 2019 款朗逸要置换'),
    (5, '陈建国', '13800000005', NULL, 'SELLER', 'VIP',    '转介绍', '长期合作车源，已收过 3 台'),
    (6, '赵磊',   '13800000006', NULL, 'SELLER', 'NORMAL', '抖音',   '咨询收车价，尚未到店'),
    (7, '孙丽',   '13800000007', NULL, 'BOTH',   'NORMAL', '门店',   '卖旧车同时看 SUV'),
    (8, '周涛',   '13800000008', NULL, 'BOTH',   'VIP',    '转介绍', '买卖双向，历史成交 2 次')
AS new
ON DUPLICATE KEY UPDATE
    `name`          = new.`name`,
    `phone`         = new.`phone`,
    `id_card`       = new.`id_card`,
    `customer_type` = new.`customer_type`,
    `level`         = new.`level`,
    `source`        = new.`source`,
    `remark`        = new.`remark`;

-- 验证：SELECT customer_type, COUNT(*) FROM customer GROUP BY customer_type;
-- 期望 BUYER=3 / SELLER=3 / BOTH=2
