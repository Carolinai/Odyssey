package org.odyssey.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 报告表读写测试：真实 SQLite，覆盖 running→done / running→failed 生命周期与列表字段。
 */
class TicketReportStoreTest {

    @TempDir
    Path tempDir;

    private TicketReportStore store;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("odyssey.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new TicketSchemaInitializer(jdbc).run(null);
        store = new TicketReportStore(jdbc);
    }

    @Test
    void createMarkDoneLifecycle() {
        long id = store.create("{\"from\":1}", "tester");
        assertTrue(id > 0);

        // 初始 running，无正文
        Map<String, Object> row = store.get(id);
        assertEquals("running", row.get("status"));
        assertNull(row.get("content_md"));
        assertNull(row.get("finished_at"));

        store.markDone(id, "# 报告\n正文");
        row = store.get(id);
        assertEquals("done", row.get("status"));
        assertEquals("# 报告\n正文", row.get("content_md"));
        assertNotNull(row.get("finished_at"));

        // 列表：不含正文大字段，带正文长度
        List<Map<String, Object>> list = store.list(10);
        assertEquals(1, list.size());
        Map<String, Object> li = list.get(0);
        assertEquals("done", li.get("status"));
        assertNull(li.get("content_md"));
        assertEquals(7L, ((Number) li.get("content_length")).longValue()); // SQLite LENGTH 按字符数（非字节）
        assertEquals("{\"from\":1}", li.get("params_json"));
        assertEquals("tester", li.get("created_by"));
    }

    @Test
    void createMarkFailedLifecycle() {
        long id = store.create("{}", "tester");
        store.markFailed(id, "AI 配置不完整");
        Map<String, Object> row = store.get(id);
        assertEquals("failed", row.get("status"));
        assertEquals("AI 配置不完整", row.get("error"));
        assertNotNull(row.get("finished_at"));
    }

    @Test
    void listOrdersNewestFirst() {
        long a = store.create("{}", "tester");
        long b = store.create("{}", "tester");
        List<Map<String, Object>> list = store.list(10);
        assertEquals(2, list.size());
        assertEquals(b, ((Number) list.get(0).get("id")).longValue());
        assertEquals(a, ((Number) list.get(1).get("id")).longValue());
    }

    @Test
    void getMissingReturnsNull() {
        assertNull(store.get(999));
    }
}
