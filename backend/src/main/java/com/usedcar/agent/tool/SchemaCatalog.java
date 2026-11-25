package com.usedcar.agent.tool;

import java.util.Set;

/**
 * 给模型看的业务库结构摘要，以及通用只读 SQL 的表/列白名单。
 *
 * 为什么硬编码而不是运行时读 information_schema：
 *   1. 硬编码能带中文说明和口径（例如「库存 = status <> CLEARED」），模型才不会瞎猜字段含义；
 *   2. 白名单是安全边界，不能由数据库当前有什么表来决定；
 *   3. 生成 prompt 时零额外 DB 往返。
 * 列名来自 V1/V8/V9/V10/V11 的真实 DDL，改表结构时这里必须同步。
 */
public final class SchemaCatalog {

    private SchemaCatalog() {
    }

    /**
     * 允许出现在只读 SQL 里的表。
     *
     * 排除两张：
     *   ai_message      —— 存聊天原文，属于内部原文；
     *   ai_agent_trace  —— V13 加的 Agent 运行轨迹，是运维日志不是经营数据。
     * 让模型能查自己上一轮写了什么属于自指，会把循环带乱，所以也不放进来。
     */
    public static final Set<String> ALLOWED_TABLES = Set.of(
            "vehicle", "vehicle_cost", "vehicle_status_log", "vehicle_certificate",
            "price_change_log", "customer", "sale_order", "after_sale",
            "vehicle_approval", "vehicle_handover", "stock_alert",
            "sys_user", "ai_conversation", "pricing_rule");

    /**
     * 隐私列黑名单：这些字段无论模型怎么写 SQL 都不给读。
     * 「读权限完全放开」指的是业务数据全部可读，隐私字段本来就在排除范围内，不是额外收紧。
     * 口令 / 身份证 / 电话 / 证件编号是用户点名的隐私；
     * 卡片与聊天原文、审批参数快照属于内部原文，同样不进模型上下文。
     */
    public static final Set<String> BLOCKED_COLUMNS = Set.of(
            "password", "id_card", "phone", "cert_no",
            "draft_json", "card_json", "payload_json", "open_key");

    public static final String SCHEMA_TEXT = """
            可用表（只允许 SELECT，列名必须显式写出，禁止 SELECT *）：

            vehicle 车辆主表：id, stock_no(库存编号), brand(品牌), model(车型), vin, plate_no,
              first_reg_date(首次上牌), mileage(里程km), purchase_price(收车价,元),
              purchase_date(收车日期), list_price(挂牌价,元), base_price(基准价,元),
              status(状态), sale_date(成交日期), clear_date(清库日期),
              owner_user_id(对接人), created_by(录入人), approved_by(审批人), remark, created_at, deleted
            vehicle_cost 成本明细：id, vehicle_id, cost_type(PURCHASE/RECONDITION/TRANSPORT/TRANSFER/OTHER),
              amount(金额,元), occur_date, remark, created_at
            vehicle_status_log 状态流水：id, vehicle_id, from_status, to_status, operator_id, remark, created_at
            vehicle_certificate 证件：id, vehicle_id, cert_type, expire_date, file_url（cert_no 证件编号是隐私，禁止查询）
            price_change_log 调价流水：id, vehicle_id, old_price, new_price, reason, operator_id, created_at
            customer 客户：id, name, customer_type, level, source, remark, created_at（phone / id_card 是隐私，禁止查询）
            sale_order 成交订单：id, order_no, vehicle_id, customer_id, deal_price(成交价,元),
              deal_date, pay_type, deposit, sales_user_id, status, created_at
            after_sale 售后：id, vehicle_id, order_id, issue_type, description, cost(成本,元),
              status(OPEN/CLOSED), handler_id, created_at, closed_at
            vehicle_approval 申请单：id, operation_type, source, target_vehicle_id, applicant_id,
              status(PENDING/APPROVED/REJECTED/CANCELLED), approver_id, approve_remark,
              result_vehicle_id, source_conversation_id, created_at, updated_at
            vehicle_handover 对接权流转：id, vehicle_id, from_user_id, to_user_id, operator_id, reason, created_at
            stock_alert 库存预警：id, vehicle_id, alert_type, alert_level, content, status, created_at, handled_at
            sys_user 员工：id, username, real_name, role, status(1启用/0停用),
              employment_status(ACTIVE/LEFT), created_at（password / phone 是隐私，禁止查询）
            ai_conversation AI 会话：id, title, owner_user_id, card_status, vehicle_id, created_at
            pricing_rule 规则表：id, rule_key, rule_value, rule_type, rule_group, description

            业务口径（写 SQL 时必须遵守）：
            1. 车辆状态枚举：PURCHASED 已收车 / ON_SALE 在售 / SOLD 已成交 / AFTER_SALE 售后 / CLEARED 已清库。
            2. 「库存 / 在库 / 在手上的车」= status <> 'CLEARED' 且 deleted = 0。
               CLEARED 已退出库存，不算在库。「在售」只指 status = 'ON_SALE'，两者不是一回事。
            3. 「在手上多久 / 库龄」= DATEDIFF(CURDATE(), purchase_date)，单位天；
               「超过一个月」用 >= 30 天。不要用 created_at 代替收车日期。
            4. 金额单位一律是元。均价用 AVG(purchase_price)，求和用 SUM(...)。
            5. 只查本门店数据，不要查 information_schema / mysql / performance_schema。
            6. 结果集最多 200 行；没写 LIMIT 系统会补 LIMIT 200，写了超过 200 会被压回 200。
            7. 【口径统一】凡涉及车辆台数、均价、金额区间、品牌分组等统计，
               WHERE 里一律带 deleted = 0 AND status <> 'CLEARED'，
               除非问题明确说「包含已清库」「全部车辆」。
               否则同一个问题换个问法答案就会变（实测「收车价超10万」出现过 3 台 / 2 台两种答案）。
               成本、订单、售后、申请单按各自表统计，不要硬套车辆状态条件。
            """;
}
