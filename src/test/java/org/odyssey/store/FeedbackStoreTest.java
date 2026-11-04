package org.odyssey.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odyssey.model.Feedback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建议反馈存储层测试：真实 SQLite，覆盖插入、默认时间倒序与各筛选条件组合。
 */
class FeedbackStoreTest {

    @TempDir
    Path tempDir;

    private FeedbackStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("odyssey.db"));
        jdbc = new JdbcTemplate(ds);
        store = new FeedbackStore(jdbc); // 构造时建表
    }

    /** 固定夹具：三人的四条反馈，类型覆盖 bug/feature/suggestion，时间递增 */
    private void insertFixture() {
        jdbc.update("INSERT INTO feedback(username, type, content, created_at) VALUES "
                + "('张三', 'bug', '客户列表搜索无结果', 1700000000000),"
                + "('李四', 'feature', '希望支持批量导出', 1700000100000),"
                + "('张三', 'suggestion', '深色模式', 1700000200000),"
                + "('王五', 'bug', '导出超时', 1700000300000)");
    }

    @Test
    void 无条件查询默认时间倒序() {
        insertFixture();
        List<Feedback> list = store.query(null, null, null, null);
        assertEquals(4, list.size());
        for (int i = 1; i < list.size(); i++) {
            assertTrue(list.get(i - 1).getCreatedAt() >= list.get(i).getCreatedAt());
        }
        assertEquals("导出超时", list.get(0).getContent()); // 最新在前
    }

    @Test
    void 按类型精确筛选() {
        insertFixture();
        List<Feedback> list = store.query(null, "bug", null, null);
        assertEquals(2, list.size());
        assertTrue(list.stream().allMatch(f -> "bug".equals(f.getType())));
    }

    @Test
    void 按用户名模糊筛选() {
        insertFixture();
        List<Feedback> list = store.query("张", null, null, null);
        assertEquals(2, list.size());
        assertTrue(list.stream().allMatch(f -> f.getUserName().contains("张")));
    }

    @Test
    void 按时间范围筛选含边界() {
        insertFixture();
        List<Feedback> list = store.query(null, null, 1700000100000L, 1700000200000L);
        assertEquals(2, list.size()); // 含 from 与 to 边界两条
    }

    @Test
    void 组合筛选与空结果() {
        insertFixture();
        assertEquals(1, store.query("李四", "feature", null, null).size());
        assertTrue(store.query("李四", "bug", null, null).isEmpty());
        assertTrue(store.query("不存在", null, null, null).isEmpty());
    }

    @Test
    void 插入返回当前时间且幂等建表() {
        long at = store.insert("赵六", "suggestion", "测试");
        assertTrue(at > 0);
        // 重复构造（应用重启场景）不丢数据、不报错
        FeedbackStore again = new FeedbackStore(jdbc);
        assertEquals(1, again.query(null, null, null, null).size());
    }
}
