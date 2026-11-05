package com.usedcar.system;

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

        // 统计业务表数量，用来确认 V1__init_schema.sql 是否执行成功
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'used_car'",
                Integer.class);
        data.put("tableCount", tableCount);
        data.put("expectedTableCount", 13);

        return ApiResult.ok(data);
    }
}
