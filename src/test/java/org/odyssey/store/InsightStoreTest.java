package org.odyssey.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解决经验表测试：真实 SQLite，覆盖待提炼队列（幂等跳过）、upsert 覆盖、
 * 分页筛选（客户/产品线/关键词覆盖经验正文与源工单标题描述）与详情/选项。
 */
class InsightStoreTest {

    @TempDir
    Path tempDir;

    private InsightStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("odyssey.db"));
        jdbc = new JdbcTemplate(ds);
        new TicketSchemaInitializer(jdbc).run(null);
        store = new InsightStore(jdbc);
        insertFixture();
    }

    /** 3 张工单：t1 已解决有经验、t2 已解决无经验、t3 进行中（永不提炼）+ t1 的 2 条评论 */
    private void insertFixture() {
        insertTicket("t1", 101, "登录失败", "密码错误提示", "done", "正大天晴", "QMS");
        insertTicket("t2", 102, "导出报错", "导出失败", "done", "澳斯康", "TMS");
        insertTicket("t3", 103, "需求评估", "待评估", "in_progress", "甘李", "QMS");
        jdbc.update("INSERT INTO ticket_comment(uuid, ticket_uuid, from_name, send_time, text)"
                        + " VALUES ('c1', 't1', '张三', 1, '重置密码后正常')");
        jdbc.update("INSERT INTO ticket_comment(uuid, ticket_uuid, from_name, send_time, text)"
                        + " VALUES ('c2', 't1', '李四', 2, '已验证')");
        store.upsert("t1", "正大天晴", "QMS", "## 问题现象\n登录失败", "glm-test");
    }

    private void insertTicket(String uuid, int number, String name, String desc,
                              String category, String customer, String product) {
        jdbc.update("INSERT INTO ticket(uuid, number, name, description_text, status_category,"
                        + " status_name, priority, assign_uuid, assign_name, customer_uuid, customer_name,"
                        + " create_time, server_update_stamp, synced_at)"
                        + " VALUES (?, ?, ?, ?, ?, '已解决', '中', 'u1', '张三', ?, ?, ?, ?, ?)",
                uuid, number, name, desc, category, customer + "-uuid", customer,
                System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis());
        jdbc.update("INSERT INTO ticket_product(ticket_uuid, product_name) VALUES (?, ?)", uuid, product);
    }

    @Test
    void pendingSkipsInsightedAndNonDone() {
        List<Map<String, Object>> pending = store.pendingTickets(10);
        assertEquals(1, pending.size()); // t2（t1 已有经验、t3 非已解决）
        assertEquals("t2", pending.get(0).get("uuid"));
        assertEquals(102, ((Number) pending.get(0).get("number")).intValue());
        assertEquals("TMS", pending.get(0).get("products"));
        assertEquals(1L, store.countPending());
    }

    @Test
    void upsertIsIdempotentPerTicket() {
        store.upsert("t2", "澳斯康", "TMS", "第一版", "glm-test");
        store.upsert("t2", "澳斯康", "TMS", "重跑后的新版本", "glm-new");
        List<Map<String, Object>> pending = store.pendingTickets(10);
        assertEquals(0, pending.size()); // 重跑后不再出现在待提炼队列
        Map<String, Object> row = store.get(insightId("t2"));
        assertEquals("重跑后的新版本", row.get("content_md")); // 覆盖而非新增
        assertEquals("glm-new", row.get("model"));
        assertEquals(102, ((Number) row.get("ticket_number")).intValue()); // JOIN 出源工单号（t2）
    }

    private long insightId(String ticketUuid) {
        return jdbc.queryForObject("SELECT id FROM insight WHERE ticket_uuid = ?", Long.class, ticketUuid);
    }

    @Test
    void pendingForUuidsFiltersAndChunks() {
        // 指定 t1（已有经验，跳过）与 t2 → 只剩 t2；t3 不在范围
        List<Map<String, Object>> r = store.pendingForUuids(Arrays.asList("t1", "t2", "t3"), 10);
        assertEquals(1, r.size());
        assertEquals("t2", r.get(0).get("uuid"));
        // 空 uuid 集合返回空
        assertEquals(0, store.pendingForUuids(Collections.emptyList(), 10).size());
    }

    @Test
    void commentsOfTicket() {
        List<Map<String, Object>> comments = store.comments("t1");
        assertEquals(2, comments.size());
        assertEquals("张三", comments.get(0).get("from_name"));
        assertTrue(store.comments("t3").isEmpty());
    }

    @Test
    void listWithFiltersAndPaging() {
        store.upsert("t2", "澳斯康", "TMS", "## 解决方式\n重启服务", "glm-test");

        // 全量：2 条，新在前（t2 的 id 更大）
        Map<String, Object> all = store.list(null, null, null, 1, 20);
        assertEquals(2L, ((Number) all.get("total")).longValue());
        assertEquals(2, ((List<?>) all.get("rows")).size());

        // 客户筛选
        assertEquals(1L, ((Number) store.list("澳斯康", null, null, 1, 20).get("total")).longValue());
        // 产品线筛选
        assertEquals(1L, ((Number) store.list(null, "QMS", null, 1, 20).get("total")).longValue());
        // 关键词命中经验正文（"重启服务" 只在 t2 经验里）
        assertEquals(1L, ((Number) store.list(null, null, "重启服务", 1, 20).get("total")).longValue());
        // 关键词命中源工单标题（"登录失败" 在 t1 标题）
        assertEquals(1L, ((Number) store.list(null, null, "登录失败", 1, 20).get("total")).longValue());
        // 分页
        Map<String, Object> p1 = store.list(null, null, null, 1, 1);
        assertEquals(1, ((List<?>) p1.get("rows")).size());
        Map<?, ?> row = (Map<?, ?>) ((List<?>) p1.get("rows")).get(0);
        assertEquals(102, ((Number) row.get("ticket_number")).intValue()); // 新在前
    }

    @Test
    void optionsAndDetail() {
        Map<String, Object> o = store.options();
        assertEquals(1, ((List<?>) o.get("customers")).size()); // 正大天晴（t2 未写经验前）
        assertNotNull(store.get(insightId("t1")));
        assertNull(store.get(999));
    }
}
