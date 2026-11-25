package com.usedcar.agent.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 数字校验器的行为测试。
 *
 * 每个用例都对应一次实测踩过的坑，改判据前先看这里：
 * 误杀正确答案和放过编造数字，这两个方向都要有断言。
 */
class AnswerNumberGuardTest {

    private final AnswerNumberGuard guard = new AnswerNumberGuard();

    @Test
    void 结果里一个数字都没有时答案报数字判幻觉() {
        Map<String, Object> result = Map.of("rowCount", 0, "rows", List.of());
        assertEquals(AnswerNumberGuard.UNKNOWN,
                guard.guard("有 3 台车符合条件。", "有朗逸吗", result));
    }

    @Test
    void 四舍五入后的金额放行() {
        Map<String, Object> result = Map.of("avgPrice", "63857.14");
        String answer = "均价 63857 元。";
        assertSame(answer, guard.guard(answer, "平均收车价多少", result));
    }

    @Test
    void 编一个近似但无关的金额被拦下() {
        Map<String, Object> result = Map.of("avgPrice", "63857.14");
        assertEquals(AnswerNumberGuard.UNKNOWN,
                guard.guard("均价 64000 元。", "平均收车价多少", result));
    }

    @Test
    void 基于结果做归类计数放行() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", 4);
        result.put("vehicles", List.of(
                Map.of("stockNo", "UC20261109001", "status", "CLEARED"),
                Map.of("stockNo", "UC20261109002", "status", "PURCHASED"),
                Map.of("stockNo", "UC20261109003", "status", "PURCHASED"),
                Map.of("stockNo", "UC20261109004", "status", "PURCHASED")));
        String answer = "查到 4 台：1 台已清库、3 台已收车。";
        assertSame(answer, guard.guard(answer, "有朗逸吗", result));
    }

    @Test
    void 超过总数的台数被拦下() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", 4);
        result.put("vehicles", List.of(Map.of("status", "PURCHASED")));
        assertEquals(AnswerNumberGuard.UNKNOWN,
                guard.guard("查到 8 台。", "有朗逸吗", result));
    }

    @Test
    void 问题里出现过的阈值放行() {
        Map<String, Object> result = Map.of("rowCount", 2, "rows", List.of(
                Map.of("stock_no", "UC20261109001", "purchase_price", "120000"),
                Map.of("stock_no", "UC20261109002", "purchase_price", "150000")));
        String answer = "收车价超过 10 万的有 2 台。";
        assertSame(answer, guard.guard(answer, "收车价超过 10 万的有几台", result));
    }

    @Test
    void 车型年款不当成编数() {
        Map<String, Object> result = Map.of("count", 1, "vehicles", List.of(
                Map.of("model", "朗逸 2021款")));
        String answer = "有 1 台朗逸 2021 款。";
        assertSame(answer, guard.guard(answer, "有朗逸吗", result));
    }

    @Test
    void 两数相减得到的差值放行() {
        // 没有 rowCount 的结果（比如单行聚合），差值只能靠 B 规则解释
        Map<String, Object> result = Map.of("maxPrice", "150000", "minPrice", "120000");
        String answer = "最高价比最低价高 30000 元。";
        assertSame(answer, guard.guard(answer, "最高和最低差多少", result));
    }
}
