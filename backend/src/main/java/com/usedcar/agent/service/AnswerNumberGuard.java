package com.usedcar.agent.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 答案数字校验器：逐个数字判断「这个数有没有证据」。
 *
 * 旧实现（AgentQueryService.guardNumbers）的判据是错的，两端都错：
 *   1. 把整个结果 JSON 和用户问题拼成一个串，只要串里出现任意一个数字就整体放行。
 *      老板问「超过 10 万的有几台」，问题里带 10，答案里写 999 也能过。
 *   2. 反过来，只要串里一个数字都没有就整体拒绝，不看答案里的数字是什么。
 *
 * 这里改成逐数字判定，每个数字必须能落到下面五类证据之一，否则整条答案换成兜底文案：
 *   A. 结果集里出现过（含金额小数的四舍五入，容差 0.1% 且不小于 0.5）；
 *   B. 结果集里两个数相减能得到的差值（覆盖「4 台里 1 台已清库、3 台在库」这类归纳）；
 *   C. 落在结果总行数以内（覆盖按状态/品牌归类的计数，n 一定不超过总台数）；
 *   D. 问题原话里出现过的数字（阈值、年份是老板给的已知条件，不是模型编的）；
 *   E. 1900-2100 的整数（车型年款，如「2021 款」）。
 *
 * 明确拦不住的：模型把「在库」和「在售」两个状态数搞混，数字本身有证据，语义是错的。
 * 那一层只能靠 prompt 里的口径约定，Java 侧不猜语义。
 */
@Component
public class AnswerNumberGuard {

    /** 未通过校验时的统一口径，与 AgentQueryService.UNKNOWN 保持一致。 */
    public static final String UNKNOWN = "这个我没查到，不敢瞎说。";

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern YEAR = Pattern.compile("^(?:19|20)\\d{2}$");
    private static final BigDecimal MIN_TOLERANCE = new BigDecimal("0.5");
    private static final BigDecimal TOLERANCE_RATIO = new BigDecimal("0.001");

    /**
     * @param answer    模型组织好的答案
     * @param userInput 老板原话，其中的数字视为已知条件
     * @param result    工具执行返回的结构化结果
     * @return 通过校验返回原文；任取一个数字找不到证据就返回 UNKNOWN
     */
    public String guard(String answer, String userInput, Map<String, Object> result) {
        return guard(answer, userInput, result == null ? List.of() : List.of(result));
    }

    /**
     * 多轮工具调用的重载：证据来自所有轮次的结果，行数取各轮最大值。
     * 只取最后一轮会误杀「第一轮查到总数、第二轮查到明细」这种正常链路。
     */
    public String guard(String answer, String userInput, List<Map<String, Object>> results) {
        if (answer == null || answer.isBlank() || results == null || results.isEmpty()) {
            return answer;
        }
        TreeSet<BigDecimal> evidence = new TreeSet<>();
        int rows = 0;
        for (Map<String, Object> result : results) {
            collectFromObject(result, evidence);
            rows = Math.max(rows, totalRows(result));
        }
        collectFromText(userInput, evidence);

        for (BigDecimal n : numbersIn(answer)) {
            if (!hasEvidence(n, evidence, rows)) {
                return UNKNOWN;
            }
        }
        return answer;
    }

    // ==================== 证据收集 ====================

    /** 递归遍历结果，任何能解析成数字的叶子都算证据。只收单个值，不从长字符串里切数字。 */
    private void collectFromObject(Object node, Set<BigDecimal> out) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                collectFromObject(v, out);
            }
            return;
        }
        if (node instanceof Collection<?> list) {
            for (Object v : list) {
                collectFromObject(v, out);
            }
            return;
        }
        if (node instanceof Number number) {
            out.add(new BigDecimal(number.toString()));
            return;
        }
        // ReadonlySqlTool 把所有值都转成了 String，这里必须再 parse 一次
        parseWhole(node.toString()).ifPresent(out::add);
    }

    /** 问题里的数字也是证据：老板说「超过 10 万」，答案里出现 10 不算编。 */
    private void collectFromText(String text, Set<BigDecimal> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            parseWhole(m.group()).ifPresent(out::add);
        }
    }

    private java.util.Optional<BigDecimal> parseWhole(String raw) {
        if (raw == null) {
            return java.util.Optional.empty();
        }
        String t = raw.trim();
        if (t.isEmpty() || t.length() > 20) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new BigDecimal(t));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    /** 答案里的数字，按出现顺序。 */
    private List<BigDecimal> numbersIn(String answer) {
        java.util.List<BigDecimal> list = new java.util.ArrayList<>();
        Matcher m = NUMBER.matcher(answer);
        while (m.find()) {
            try {
                list.add(new BigDecimal(m.group()));
            } catch (NumberFormatException ignored) {
                // 超长串当不了数字，跳过
            }
        }
        return list;
    }

    /** 结果总行数：优先 rowCount，其次 count / total，最后数 rows 或 vehicles 的长度。 */
    private int totalRows(Map<String, Object> result) {
        for (String key : List.of("rowCount", "count", "total")) {
            Object v = result.get(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
        }
        for (String key : List.of("rows", "vehicles")) {
            Object v = result.get(key);
            if (v instanceof Collection<?> c) {
                return c.size();
            }
        }
        return 0;
    }

    // ==================== 判定 ====================

    private boolean hasEvidence(BigDecimal n, TreeSet<BigDecimal> evidence, int rows) {
        if (n.signum() == 0) {
            return true;
        }
        if (isYear(n)) {
            return true;
        }
        // A. 结果里直接出现过
        if (near(evidence, n)) {
            return true;
        }
        // B. 两个结果数相减能得到它（总数 - 已清库 = 在库）
        for (BigDecimal v : evidence) {
            if (near(evidence, v.subtract(n))) {
                return true;
            }
        }
        // C. 不超过总行数的计数，视为按某列归类的结果
        if (rows > 0 && n.compareTo(BigDecimal.valueOf(rows)) <= 0) {
            return true;
        }
        return false;
    }

    private static boolean isYear(BigDecimal n) {
        if (n.scale() > 0) {
            return false;
        }
        return YEAR.matcher(n.toPlainString()).matches();
    }

    /** TreeSet 二分找最近值，避免「结果 200 行 × 10 列」时的两两比较。 */
    private static boolean near(TreeSet<BigDecimal> sorted, BigDecimal target) {
        BigDecimal floor = sorted.floor(target);
        BigDecimal ceil = sorted.ceiling(target);
        return (floor != null && closeEnough(floor, target)) || (ceil != null && closeEnough(ceil, target));
    }

    /**
     * 容差 = max(0.5, |基准值| × 0.1%)。
     * 63857.14 与 63857 差 0.14，放行（模型口语化四舍五入）；
     * 63857.14 与 64000 差 142.86，超出 63.86，拒绝（编了一个近似但无关的数）。
     */
    private static boolean closeEnough(BigDecimal base, BigDecimal target) {
        BigDecimal tolerance = base.abs().multiply(TOLERANCE_RATIO);
        if (tolerance.compareTo(MIN_TOLERANCE) < 0) {
            tolerance = MIN_TOLERANCE;
        }
        return base.subtract(target).abs().compareTo(tolerance) <= 0;
    }
}
