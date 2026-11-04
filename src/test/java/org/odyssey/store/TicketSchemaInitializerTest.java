package org.odyssey.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单助手 SQLite 表结构冒烟：建表幂等、基本读写、UNIQUE 约束生效。
 */
class TicketSchemaInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void 建表幂等且可读写() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("odyssey.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TicketSchemaInitializer initializer = new TicketSchemaInitializer(jdbc);

        initializer.run(null);
        initializer.run(null); // 二次执行不抛错（幂等）

        // ticket 主表读写（数据取自真实样例工单 #378800）
        jdbc.update("INSERT INTO ticket(uuid, number, name, status_category, customer_name, create_time) VALUES(?,?,?,?,?,?)",
                "MPMsadtYjJgE5C6A", 378800, "【宜昌人福】QMS与MDM的对接", "to_do", "宜昌人福", 1793611085494L);
        String name = jdbc.queryForObject(
                "SELECT name FROM ticket WHERE uuid = ?", String.class, "MPMsadtYjJgE5C6A");
        assertEquals("【宜昌人福】QMS与MDM的对接", name);

        // 多值产品线关联
        jdbc.update("INSERT INTO ticket_product(ticket_uuid, product_uuid, product_name) VALUES(?,?,?)",
                "MPMsadtYjJgE5C6A", "NfvccPP5F3tNhaPe", "QMS");
        List<Map<String, Object>> products = jdbc.queryForList(
                "SELECT product_name FROM ticket_product WHERE ticket_uuid = ?", "MPMsadtYjJgE5C6A");
        assertEquals(1, products.size());
        assertEquals("QMS", products.get(0).get("product_name"));

        // insight 的 ticket_uuid UNIQUE：重复提炼写不进去（重跑幂等的库层保障）
        // 注：Spring 未内置 SQLite 错误码翻译，约束冲突抛 UncategorizedSQLException 而非 DuplicateKeyException
        jdbc.update("INSERT INTO insight(ticket_uuid, content_md, status) VALUES(?,?,?)",
                "t1", "经验内容", "done");
        Exception e = assertThrows(Exception.class, () ->
                jdbc.update("INSERT INTO insight(ticket_uuid, content_md, status) VALUES(?,?,?)",
                        "t1", "经验内容2", "done"));
        assertTrue(String.valueOf(e.getMessage()).contains("UNIQUE"),
                "应为 UNIQUE 约束冲突，实际：" + e.getMessage());

        // 建表在磁盘上可见（单文件库随数据目录走）
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("odyssey.db")), "数据库文件应在数据目录下");
    }
}
