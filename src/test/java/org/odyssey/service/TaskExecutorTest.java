package org.odyssey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odyssey.store.TaskStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台任务框架测试：提交→进度→完成/失败的状态机、错误信息可见、重启清理无僵尸 running。
 */
class TaskExecutorTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private TaskStore store;
    private TaskExecutor executor;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("odyssey.db"));
        jdbc = new JdbcTemplate(ds);
        new org.odyssey.store.TicketSchemaInitializer(jdbc).run(null);
        store = new TaskStore(jdbc);
        executor = new TaskExecutor(store);
    }

    /** 轮询等待任务进入目标状态（工作线程异步执行，测试里用小步轮询代替固定 sleep） */
    private Map<String, Object> awaitStatus(long id, String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        Map<String, Object> task = null;
        while (System.currentTimeMillis() < deadline) {
            task = store.get(id);
            if (task != null && status.equals(task.get("status"))) return task;
            Thread.sleep(20);
        }
        assertNotNull(task, "任务不存在");
        assertEquals(status, task.get("status"), "等待任务超时，当前: " + task.get("status")
                + " error=" + task.get("error"));
        return task;
    }

    @Test
    void submitRunsToDoneWithProgress() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        long id = executor.submit("demo", paramsOf("k", "v"), "tester", ctx -> {
            started.countDown();
            ctx.progress(30, "准备语料");
            ctx.progress(80, "调用 AI");
            // 进度越界自动收敛到 0-100
            ctx.progress(150, "收尾");
        });
        assertTrue(id > 0);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        Map<String, Object> done = awaitStatus(id, "done");
        assertEquals(100L, ((Number) done.get("progress")).longValue());
        assertNotNull(done.get("finished_at"));
        assertNull(done.get("error"));
        // 最后一次进度文案保留（越界的 150 收敛为 100）
        assertEquals("收尾", done.get("stage"));
    }

    @Test
    void failureStoresErrorMessage() throws Exception {
        long id = executor.submit("demo", paramsOf("k", "v"), "tester", ctx -> {
            ctx.progress(10, "起步");
            throw new IllegalStateException("AI 网关连接超时");
        });
        Map<String, Object> failed = awaitStatus(id, "failed");
        assertEquals("AI 网关连接超时", failed.get("error"));
        assertNotNull(failed.get("finished_at"));
        assertEquals(10L, ((Number) failed.get("progress")).longValue()); // 进度停在中途
    }

    @Test
    void bootCleanupFailsZombieRunningTasks() {
        // 模拟上次进程遗留：手工插入 pending / running 两条
        jdbc.update("INSERT INTO task(type, params_json, status, progress, created_by, created_at)"
                + " VALUES ('ai_report', '{}', 'running', 40, 'u1', 1)");
        jdbc.update("INSERT INTO task(type, params_json, status, progress, created_by, created_at)"
                + " VALUES ('insight_batch', '{}', 'pending', 0, 'u1', 2)");
        jdbc.update("INSERT INTO task(type, params_json, status, progress, created_by, created_at)"
                + " VALUES ('ai_report', '{}', 'done', 100, 'u1', 3)");

        executor.cleanupOnBoot();

        // 遗留的 pending/running → failed 且带原因；已完成的 done 不受影响
        assertEquals(2L, jdbc.queryForList("SELECT id FROM task WHERE status='failed'").size());
        assertEquals(1L, jdbc.queryForList("SELECT id FROM task WHERE status='done'").size());
        Map<String, Object> zombie = jdbc.queryForMap(
                "SELECT * FROM task WHERE type='ai_report' AND created_at=1");
        assertEquals("应用重启，任务中断", zombie.get("error"));
    }

    @Test
    void listReturnsRecentTasksNewestFirst() throws Exception {
        long first = executor.submit("demo", paramsOf("i", 1), "tester", ctx -> { });
        awaitStatus(first, "done");
        long second = executor.submit("demo", paramsOf("i", 2), "tester", ctx -> { });
        awaitStatus(second, "done");

        Map<String, Object> resp = new HashMap<>();
        resp.put("tasks", store.list(10));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> tasks = (java.util.List<Map<String, Object>>) resp.get("tasks");
        assertEquals(2, tasks.size());
        assertEquals(second, ((Number) tasks.get(0).get("id")).longValue()); // 新在前
    }

    private static Map<String, Object> paramsOf(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(k, v);
        return m;
    }
}
