package com.usedcar.system;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.usedcar.common.ApiResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。用于验证三件事：应用起来了、数据库连上了、表建好了。
 *
 * 这个接口不校验登录（已在 SaTokenConfig 里放行），运维探活要用。
 *
 * 关于「表数」，这里刻意区分两个口径，不要混成一个数字：
 *   ddlTableCount    → 库里实际建了几张表（V1~V7 脚本执行成功的证明）
 *   wiredTableCount  → Java 侧接了几张表（带 @TableName 的实体个数）
 * 面试里被问「15 张都在用吗」，答案是「15 张建表、15 张接线、0 张预留」，
 * 而不是含糊地说「15 张表」。V7 的 2 张 AI 会话、V9 的申请单、V10 的流转记录，
 * 以及最后接线的 sale_order / after_sale 全部已接线，没有预留表了。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入而不是 @Autowired 字段注入。
     * 原因：字段注入拿不到 final，测试时无法在不启动 Spring 的情况下 new 出来。
     */
    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", "usedcar-server");

        // SELECT 1 是最轻的连通性探测，不依赖任何业务表
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        data.put("dbConnected", one != null && one == 1);

        // DDL 建表数：只证明建表脚本跑过，跟 Java 有没有接线完全无关
        Integer ddlTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'used_car'",
                Integer.class);
        data.put("ddlTableCount", ddlTableCount);
        // 期望值本来就该硬编码：它表达的是「这份脚本应该建出 16 张表」
        // V1 的 11 张 + V7 的 2 张 + V9 的 vehicle_approval + V10 的 vehicle_handover
        // + V13 的 ai_agent_trace（Agent 工具调用轨迹）
        data.put("expectedDdlTableCount", 16);

        // 已接线表数：直接读 MyBatis-Plus 的 TableInfo 元数据缓存，
        // 值等于带 @TableName 的实体类个数。新增实体会自动反映，
        // 不用再维护一份「在用表清单」——第二份真相迟早会和代码漂移。
        data.put("wiredTableCount", TableInfoHelper.getTableInfos().size());

        return ApiResult.ok(data);
    }
}
