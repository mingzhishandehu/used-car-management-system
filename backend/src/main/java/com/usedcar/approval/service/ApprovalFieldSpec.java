package com.usedcar.approval.service;

import java.util.List;
import java.util.Map;

/**
 * 「复制重提」表单的字段规格，后端唯一来源，随接口发给前端。
 *
 * 和 IntakeFieldSpec 同一个思路：前端不自己维护一份字段清单，
 * 否则后端 DTO 加了个字段、前端漏了，重提时该字段就会悄悄丢失。
 *
 * 只列「允许员工在重提时改」的字段。ownerUserId 这类归属字段不列，
 * 重提时沿用原 payload 的值，避免员工顺手把车改挂到别人名下。
 */
public final class ApprovalFieldSpec {

    private ApprovalFieldSpec() {
    }

    public record Option(String value, String label) {
    }

    public record Field(String key, String label, String type, List<Option> options) {
        static Field text(String key, String label) {
            return new Field(key, label, "TEXT", List.of());
        }

        static Field number(String key, String label) {
            return new Field(key, label, "NUMBER", List.of());
        }

        static Field date(String key, String label) {
            return new Field(key, label, "DATE", List.of());
        }

        static Field select(String key, String label, List<Option> options) {
            return new Field(key, label, "SELECT", options);
        }
    }

    // 超过 10 组就不能用 Map.of（最多 10 对），改 Map.ofEntries
    public static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("AGENT_BATCH", "AI 批量业务操作（需管理员确认）"),
            Map.entry("CREATE_VEHICLE", "收车入库"),
            Map.entry("AI_INTAKE_CREATE", "AI 收车录入"),
            Map.entry("UPDATE_VEHICLE", "修改车辆信息"),
            Map.entry("CHANGE_STATUS", "车辆状态流转"),
            Map.entry("CHANGE_PRICE", "调整挂牌价"),
            Map.entry("ADD_COST", "追加成本"),
            Map.entry("ADD_CERTIFICATE", "新增车辆证件"),
            Map.entry("DELETE_CERTIFICATE", "删除车辆证件"),
            Map.entry("CREATE_SALE_ORDER", "成交登记"),
            Map.entry("ADD_AFTER_SALE", "新增售后单"),
            Map.entry("CLOSE_AFTER_SALE", "售后结案"));

    private static final List<Field> CREATE_FIELDS = List.of(
            Field.text("brand", "品牌"),
            Field.text("model", "车型"),
            Field.number("purchasePrice", "收车价（元）"),
            Field.date("purchaseDate", "收车日期"),
            Field.text("vin", "VIN 车架号"),
            Field.date("firstRegDate", "首次上牌日期"),
            Field.number("mileage", "表显里程（km）"),
            Field.text("plateNo", "车牌号"),
            Field.number("listPrice", "挂牌价（元）"),
            Field.text("remark", "备注"));

    private static final List<Field> UPDATE_FIELDS = List.of(
            Field.text("brand", "品牌"),
            Field.text("model", "车型"),
            Field.text("plateNo", "车牌号"),
            Field.text("vin", "VIN 车架号"),
            Field.date("firstRegDate", "首次上牌日期"),
            Field.number("mileage", "表显里程（km）"),
            Field.text("remark", "备注"));

    private static final List<Field> STATUS_FIELDS = List.of(
            Field.select("targetStatus", "目标状态", List.of(
                    new Option("ON_SALE", "在售"),
                    new Option("SOLD", "已成交"),
                    new Option("AFTER_SALE", "售后"),
                    new Option("CLEARED", "已清库"))),
            Field.text("remark", "变更说明"));

    private static final List<Field> PRICE_FIELDS = List.of(
            Field.number("newPrice", "新的挂牌价（元）"),
            Field.text("reason", "调价原因"));

    private static final List<Field> COST_FIELDS = List.of(
            Field.select("costType", "成本类型", List.of(
                    new Option("RECONDITION", "整备"),
                    new Option("TRANSPORT", "运输"),
                    new Option("TRANSFER", "过户"),
                    new Option("OTHER", "其他"))),
            Field.number("amount", "金额（元）"),
            Field.date("occurDate", "发生日期"),
            Field.text("remark", "说明"));

    private static final List<Field> CERT_FIELDS = List.of(
            Field.select("certType", "证件类型", List.of(
                    new Option("REGISTER", "登记证"),
                    new Option("DRIVING", "行驶证"),
                    new Option("INSURANCE", "交强险"),
                    new Option("INSPECT", "年检"))),
            Field.text("certNo", "证件编号"),
            Field.date("expireDate", "到期日期"),
            Field.text("fileUrl", "文件地址"));

    private static final List<Field> SALE_ORDER_FIELDS = List.of(
            Field.number("customerId", "买方客户 id"),
            Field.number("dealPrice", "成交价（元）"),
            Field.date("dealDate", "成交日期"),
            Field.select("payType", "付款方式", List.of(
                    new Option("FULL", "全款"),
                    new Option("LOAN", "按揭"))),
            Field.number("deposit", "定金（元）"),
            Field.text("remark", "备注"));

    private static final List<Field> AFTER_SALE_FIELDS = List.of(
            Field.select("issueType", "问题类型", List.of(
                    new Option("REPAIR", "维修"),
                    new Option("REFUND", "退款"),
                    new Option("COMPLAINT", "投诉"),
                    new Option("OTHER", "其他"))),
            Field.text("description", "问题描述"),
            Field.number("cost", "售后成本（元）"));

    public static final Map<String, List<Field>> SPEC = Map.ofEntries(
            Map.entry("AGENT_BATCH", List.of()),
            Map.entry("CREATE_VEHICLE", CREATE_FIELDS),
            Map.entry("AI_INTAKE_CREATE", CREATE_FIELDS),
            Map.entry("UPDATE_VEHICLE", UPDATE_FIELDS),
            Map.entry("CHANGE_STATUS", STATUS_FIELDS),
            Map.entry("CHANGE_PRICE", PRICE_FIELDS),
            Map.entry("ADD_COST", COST_FIELDS),
            Map.entry("ADD_CERTIFICATE", CERT_FIELDS),
            // 删除没有可改的参数，只允许原样重提，所以字段清单为空
            Map.entry("DELETE_CERTIFICATE", List.of()),
            Map.entry("CREATE_SALE_ORDER", SALE_ORDER_FIELDS),
            Map.entry("ADD_AFTER_SALE", AFTER_SALE_FIELDS),
            Map.entry("CLOSE_AFTER_SALE", List.of()));

    public static String label(String operationType) {
        return LABELS.getOrDefault(operationType, operationType);
    }
}
