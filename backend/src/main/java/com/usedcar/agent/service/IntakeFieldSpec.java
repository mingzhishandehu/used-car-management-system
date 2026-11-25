package com.usedcar.agent.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 卡片字段规格。前后端唯一的字段清单来源。
 *
 * 之前最容易出错的地方：前端写一份「必填字段」、后端 DTO 里另有一份校验注解，
 * 两边一漂移，就会出现「前端说信息齐了，后端 400」。
 * 所以这里把 10 个字段、每组归属、每项的控件类型一次性定义好，
 * 随 IntakeCardDTO.spec 发给前端，前端只按 spec 渲染，不再自己维护清单。
 *
 * 分组口径（用户 2026-11-08 拍板）：
 *   REQUIRED 4 个 —— 与 VehicleCreateDTO 的 @NotBlank / @NotNull 完全一致，必须补齐
 *   OPTIONAL 4 个 —— 会追问，但老板可以点「跳过这些」直接确认
 *   EXTRA    2 个 —— 不追问，卡片里可直接填
 * ownerUserId 属于内部字段，不给老板填，也不塞进卡片。
 */
public final class IntakeFieldSpec {

    private IntakeFieldSpec() {
    }

    public enum Group { REQUIRED, OPTIONAL, EXTRA }

    public enum FieldType { TEXT, MONEY, DATE, INTEGER }

    public record Field(String key, String label, Group group, FieldType type, String hint) {
    }

    public static final List<Field> ALL = List.of(
            new Field("brand", "品牌", Group.REQUIRED, FieldType.TEXT, "例如 大众"),
            new Field("model", "车型", Group.REQUIRED, FieldType.TEXT, "例如 朗逸 2021款 1.5L 自动舒适版"),
            new Field("purchasePrice", "收车价（元）", Group.REQUIRED, FieldType.MONEY, "只填数字，单位元"),
            new Field("purchaseDate", "收车日期", Group.REQUIRED, FieldType.DATE, "YYYY-MM-DD，资金成本从这天起算"),
            new Field("vin", "VIN 车架号", Group.OPTIONAL, FieldType.TEXT, "17 位，可跳过"),
            new Field("firstRegDate", "首次上牌日期", Group.OPTIONAL, FieldType.DATE, "YYYY-MM-DD，可跳过"),
            new Field("mileage", "表显里程（km）", Group.OPTIONAL, FieldType.INTEGER, "只填数字，可跳过"),
            new Field("plateNo", "车牌号", Group.OPTIONAL, FieldType.TEXT, "例如 豫A12345，可跳过"),
            new Field("listPrice", "挂牌价（元）", Group.EXTRA, FieldType.MONEY, "可留空，转在售前再设"),
            new Field("remark", "备注", Group.EXTRA, FieldType.TEXT, "车况、来源等")
    );

    private static final Map<String, Field> BY_KEY = ALL.stream()
            .collect(Collectors.toMap(Field::key, f -> f, (a, b) -> a, LinkedHashMap::new));

    public static final Set<String> KEYS = BY_KEY.keySet();

    public static final List<Field> REQUIRED = ALL.stream()
            .filter(f -> f.group() == Group.REQUIRED).toList();

    public static final List<Field> OPTIONAL = ALL.stream()
            .filter(f -> f.group() == Group.OPTIONAL).toList();

    public static Field byKey(String key) {
        return BY_KEY.get(key);
    }

    /**
     * 把模型或前端传来的原始值归一化成卡片能存的标准值。
     *
     * 这是不可信输入的唯一入口：模型可能输出 "6.8万"、6.8e4、"2026/11/8"、
     * 甚至一个没在清单里的字段。白名单外的 key 直接丢，类型不对的值返回 null
     * （等于这一项没抽到），绝不让脏值写进草稿、更不让它流到 VehicleCreateDTO。
     *
     * @param today 用于把「今天」落成具体日期，避免模型自己编一个日期
     * @return 归一化后的值；无法解析或非法返回 null
     */
    public static Object normalize(String key, Object raw, LocalDate today) {
        Field field = BY_KEY.get(key);
        if (field == null || raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return switch (field.type()) {
                case TEXT -> text;
                case MONEY -> parseMoney(text);
                case INTEGER -> parseInteger(text);
                case DATE -> parseDate(text, today);
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 金额统一存成字符串，避免 JSON 里的 double 造成 68000.00000000001 这种脏值。 */
    private static String parseMoney(String text) {
        String cleaned = text.replaceAll("[\\s,，元]", "");
        BigDecimal value = parseAmount(cleaned);
        if (value == null || value.signum() < 0) {
            return null;
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static Integer parseInteger(String text) {
        String cleaned = text.replaceAll("[\\s,，公里kmKM]", "");
        BigDecimal value = parseAmount(cleaned);
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 0) {
            return null;
        }
        return value.intValueExact();
    }

    /** 支持中文口语：6.8万 / 6万8 / 68000 / 6.8w。 */
    private static BigDecimal parseAmount(String text) {
        if (text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase();
        try {
            if (lower.endsWith("万") || lower.endsWith("w")) {
                String num = lower.substring(0, lower.length() - 1);
                if (num.isEmpty()) {
                    return new BigDecimal("10000");
                }
                return new BigDecimal(num).multiply(new BigDecimal("10000"));
            }
            return new BigDecimal(lower);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseDate(String text, LocalDate today) {
        String cleaned = text.replace('/', '-');
        if ("今天".equals(cleaned) || "today".equalsIgnoreCase(cleaned)) {
            return today.toString();
        }
        if ("昨天".equals(cleaned)) {
            return today.minusDays(1).toString();
        }
        try {
            return LocalDate.parse(cleaned).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 白名单过滤 + 全量归一化。模型返回的 extra 字段在这里被丢掉。 */
    public static Map<String, Object> sanitize(Map<String, Object> raw, LocalDate today) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!KEYS.contains(entry.getKey())) {
                continue;
            }
            Object value = normalize(entry.getKey(), entry.getValue(), today);
            if (value != null) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /** 从草稿里挑出仍为空的字段，按 ALL 的顺序返回，前端展示顺序才稳定。 */
    public static List<String> missing(Map<String, Object> draft, List<Field> group) {
        return group.stream()
                .filter(f -> {
                    Object v = draft.get(f.key());
                    return v == null || String.valueOf(v).isBlank();
                })
                .map(Field::key)
                .toList();
    }
}
