package com.usedcar.agent.tool;

import com.usedcar.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用只读查询工具：模型写 SELECT，Java 侧做安全校验后执行。
 *
 * 这是「读权限放开」的实现，但放开的是**查询表达能力**，不是数据库权限：
 *   1. 只允许单条 SELECT，禁止任何写关键字、多语句、注释；
 *   2. FROM / JOIN 的表名必须落在 SchemaCatalog.ALLOWED_TABLES 白名单里；
 *   3. 禁止 SELECT *，敏感列（password / id_card / 卡片原文 / 审批快照）出现即拒绝；
 *   4. 强制 LIMIT 上限 200，执行后再裁一次行数和列；
 *   5. 全程用只读语义的 queryForList，没有任何写操作入口。
 *
 * 模型从头到尾拿不到 Connection、拿不到 DataSource，也拿不到写方法。
 */
@Service
public class ReadonlySqlTool {

    public static final String TOOL_NAME = "run_readonly_sql";
    private static final int MAX_ROWS = 200;

    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|alter|create|truncate|replace|grant|revoke|"
                    + "call|execute|set|lock|unlock|handler|load_file|outfile|dumpfile|"
                    + "sleep|benchmark|information_schema|performance_schema|mysql\\.|sys\\.)\\b");
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+`?([a-zA-Z_][a-zA-Z0-9_]*)`?");
    private static final Pattern TRAILING_LIMIT = Pattern.compile(
            "(?i)\\blimit\\s+(\\d+)(?:\\s*,\\s*(\\d+))?\\s*;?\\s*$");

    private final JdbcTemplate jdbcTemplate;

    public ReadonlySqlTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 给模型看的工具说明，会拼进意图路由 prompt。 */
    public String description() {
        return "- " + TOOL_NAME + "(sql): 执行一条只读 SELECT，可自由写条件、分组、聚合、日期函数、多表 JOIN；"
                + "当固定工具算不出（库龄、均价、区间、分组排名、跨表统计等）时必须用它。"
                + "sql 里必须显式写列名，禁止 SELECT *，只能查下面列出的白名单表。";
    }

    /**
     * 校验 + 执行。返回的 Map 里 rows 是结果行，rowCount 是行数。
     * SQL 写错时抛 BizException(400)，上层会把错误信息反馈给模型改写一次。
     */
    public Map<String, Object> execute(String rawSql) {
        String sql = validate(rawSql);
        try {
            List<Map<String, Object>> raw = jdbcTemplate.queryForList(sql);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
                Map<String, Object> clean = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (isBlockedColumn(e.getKey())) {
                        continue;
                    }
                    Object v = e.getValue();
                    clean.put(e.getKey(), v == null ? null : String.valueOf(v));
                }
                rows.add(clean);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rowCount", rows.size());
            out.put("rows", rows);
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 不把 JDBC 原始异常（可能带连接信息）返回给模型，只给一句可修正的提示
            throw new BizException(400, "SQL 执行失败，请检查表名和列名：" + shortMessage(e));
        }
    }

    /** 把模型给的 SQL 变成可安全执行的 SQL；不合法直接拒绝。 */
    public String validate(String rawSql) {
        if (!StringUtils.hasText(rawSql)) {
            throw new BizException(400, "SQL 不能为空");
        }
        String sql = rawSql.trim();
        // 剥掉模型偶尔带的 ```sql 围栏
        if (sql.startsWith("```")) {
            int nl = sql.indexOf('\n');
            if (nl > 0) {
                sql = sql.substring(nl + 1);
            }
            int fence = sql.lastIndexOf("```");
            if (fence >= 0) {
                sql = sql.substring(0, fence);
            }
            sql = sql.trim();
        }
        sql = sql.replaceAll(";\\s*$", "").trim();

        // 单条语句：中间出现分号一律拒绝，杜绝堆叠注入
        if (sql.contains(";")) {
            throw new BizException(400, "只允许单条 SELECT 语句");
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT")) {
            throw new BizException(400, "只允许 SELECT 查询");
        }
        if (DANGEROUS.matcher(sql).find()) {
            throw new BizException(400, "SQL 里包含不允许的关键字");
        }
        if (sql.contains("--") || sql.contains("/*") || sql.contains("*/") || sql.contains("#")) {
            throw new BizException(400, "SQL 里不允许注释");
        }
        // 注意不能写成 \\*\\b：星号不是单词字符，后面的 \\b 永远匹配不上，规则会静默失效
        if (upper.matches("(?s).*\\bSELECT\\s+\\*.*") || sql.contains(".*")) {
            throw new BizException(400, "必须显式写列名，禁止 SELECT * 或 表.*");
        }
        for (String col : SchemaCatalog.BLOCKED_COLUMNS) {
            if (Pattern.compile("(?i)\\b" + Pattern.quote(col) + "\\b").matcher(sql).find()) {
                throw new BizException(400, "SQL 里包含不允许查询的敏感列：" + col);
            }
        }

        Matcher tableMatcher = TABLE_REF.matcher(sql);
        Set<String> allowed = SchemaCatalog.ALLOWED_TABLES;
        int refCount = 0;
        while (tableMatcher.find()) {
            refCount++;
            String table = tableMatcher.group(1).toLowerCase(Locale.ROOT);
            if (!allowed.contains(table)) {
                throw new BizException(400, "表 " + table + " 不在只读查询白名单里");
            }
        }
        if (refCount == 0) {
            throw new BizException(400, "SQL 里找不到合法的 FROM 表名");
        }

        // 强制行数上限：末尾有 LIMIT 就压回 200，没有就补上
        Matcher limit = TRAILING_LIMIT.matcher(sql);
        if (limit.find()) {
            int n = Integer.parseInt(limit.group(2) != null ? limit.group(2) : limit.group(1));
            if (n > MAX_ROWS) {
                sql = sql.substring(0, limit.start()) + " LIMIT " + MAX_ROWS;
            }
        } else {
            sql = sql + " LIMIT " + MAX_ROWS;
        }
        return sql;
    }

    private static boolean isBlockedColumn(String col) {
        if (col == null) {
            return false;
        }
        String c = col.toLowerCase(Locale.ROOT);
        for (String blocked : SchemaCatalog.BLOCKED_COLUMNS) {
            if (c.equals(blocked) || c.contains(blocked)) {
                return true;
            }
        }
        return false;
    }

    private static String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 160 ? msg.substring(0, 160) : msg;
    }
}
