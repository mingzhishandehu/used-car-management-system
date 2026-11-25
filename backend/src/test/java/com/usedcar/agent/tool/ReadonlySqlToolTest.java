package com.usedcar.agent.tool;

import com.usedcar.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读 SQL 校验器的边界测试。
 *
 * 存在的意义：光靠「模型自己说不能改」不算写权限收紧。
 * 这里直接拿真实的写语句去撞校验层，证明即使模型生成了 UPDATE / DELETE，
 * Java 侧也会在碰数据库之前把它挡下来。
 * jdbcTemplate 传 null 即可：validate() 只做字符串校验，不碰连接。
 */
class ReadonlySqlToolTest {

    private final ReadonlySqlTool tool = new ReadonlySqlTool(null);

    @Test
    void 写语句一律拒绝() {
        assertRejected("UPDATE vehicle SET status = 'CLEARED' WHERE id = 1");
        assertRejected("DELETE FROM vehicle WHERE id = 1");
        assertRejected("INSERT INTO vehicle(brand) VALUES('大众')");
        assertRejected("DROP TABLE vehicle");
        assertRejected("ALTER TABLE vehicle ADD COLUMN x INT");
        assertRejected("TRUNCATE TABLE vehicle");
    }

    @Test
    void 堆叠注入与注释被拒绝() {
        assertRejected("SELECT id FROM vehicle; DROP TABLE vehicle");
        assertRejected("SELECT id FROM vehicle -- 注释");
        assertRejected("SELECT id FROM vehicle /* 注释 */");
    }

    @Test
    void 非白名单表与敏感列被拒绝() {
        assertRejected("SELECT id FROM mysql.user");
        assertRejected("SELECT table_name FROM information_schema.tables");
        assertRejected("SELECT id, password FROM sys_user");
        assertRejected("SELECT id_card FROM customer");
        assertRejected("SELECT payload_json FROM vehicle_approval");
    }

    @Test
    void 用户点名的隐私列一律拒绝() {
        assertRejected("SELECT phone FROM customer");
        assertRejected("SELECT phone FROM sys_user");
        assertRejected("SELECT cert_no FROM vehicle_certificate");
        assertRejected("SELECT id_card FROM customer");
    }

    @Test
    void 禁止星号() {
        assertRejected("SELECT * FROM vehicle");
        assertRejected("SELECT v.* FROM vehicle v");
    }

    @Test
    void 合法只读查询放行并强制补上限() {
        String sql = tool.validate("SELECT id, brand FROM vehicle WHERE deleted = 0");
        assertTrue(sql.toUpperCase().contains("LIMIT 200"), "没写 LIMIT 时应自动补 200");

        String capped = tool.validate("SELECT id FROM vehicle LIMIT 9999");
        assertTrue(capped.toUpperCase().endsWith("LIMIT 200"), "超过上限应压回 200");

        assertEquals("SELECT id FROM vehicle LIMIT 5",
                tool.validate("SELECT id FROM vehicle LIMIT 5"));
    }

    private void assertRejected(String sql) {
        BizException e = assertThrows(BizException.class, () -> tool.validate(sql),
                "本应被拒绝的 SQL 却放行了: " + sql);
        assertTrue(e.getMessage() != null && !e.getMessage().isBlank());
    }
}
