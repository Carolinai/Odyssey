package org.odyssey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odyssey.model.OnesComment;
import org.odyssey.model.OnesHistory;
import org.odyssey.model.OnesRef;
import org.odyssey.model.OnesTaskMessages;
import org.odyssey.model.OnesTicket;
import org.odyssey.model.UserConfig;
import org.odyssey.store.GlobalConfigStore;
import org.odyssey.store.InsightStore;
import org.odyssey.store.TicketSchemaInitializer;
import org.odyssey.store.TicketStore;
import org.odyssey.store.TaskStore;
import org.odyssey.store.UserConfigStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单同步编排测试：假 ONES 客户端 + 真实 SQLite，覆盖全量、增量、失败、
 * Cookie 个人化（未配置拦截 / 自动同步取 Cookie）路径。
 */
class TicketSyncServiceTest {

    /** 测试用触发用户（同步用其个人 Cookie） */
    private static final String TEST_USER = "tester";

    @TempDir
    Path tempDir;

    private TicketStore store;
    private UserConfigStore configStore;
    private GlobalConfigStore globalConfigStore;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("odyssey.db"));
        jdbc = new JdbcTemplate(ds);
        new TicketSchemaInitializer(jdbc).run(null);
        store = new TicketStore(jdbc);
        configStore = new UserConfigStore(tempDir);
        globalConfigStore = new GlobalConfigStore(tempDir);
    }

    /** 给测试用户配置一份个人 Cookie（手动同步前置条件） */
    private void saveUserCookie(String user) {
        UserConfig cfg = new UserConfig();
        cfg.setOnesCookie("ones-uid=test;");
        configStore.save(user, cfg);
    }

    /** 构造被测服务（附真实依赖；AI 配置默认未保存，经验提炼联动即便触发也只空转） */
    private TicketSyncService newService(OnesClient client) {
        TaskExecutor taskExecutor = new TaskExecutor(new TaskStore(jdbc));
        InsightService insightService = new InsightService(
                new InsightStore(jdbc), new AiGatewayClient(configStore), configStore);
        return new TicketSyncService(client, store, configStore, globalConfigStore,
                taskExecutor, insightService);
    }

    // ---- 假客户端 ----

    /** 固定返回 t1（全量）/ t2（增量）两张工单，动态流各一条评论 + 一条历史 */
    private OnesClient fakeClient(AtomicInteger fullCalls, AtomicInteger incCalls) {
        return new OnesClient() {
            @Override
            public List<OnesTicket> fetchTicketsCreatedSince(String cookie, long sinceMs) {
                fullCalls.incrementAndGet();
                return Collections.singletonList(ticket("t1", 100, "张三公司"));
            }

            @Override
            public List<OnesTicket> fetchTicketsUpdatedSince(String cookie, long sinceMs) {
                incCalls.incrementAndGet();
                return Collections.singletonList(ticket("t2", 200, "李四公司"));
            }

            @Override
            public Map<String, String> fetchUserNames(String cookie) {
                Map<String, String> names = new HashMap<>();
                names.put("u1", "张三");
                return names;
            }

            @Override
            public OnesTaskMessages fetchMessages(String cookie, String taskUuid) {
                OnesTaskMessages m = new OnesTaskMessages();
                // 评论 uuid 全局唯一（真实 ONES 语义），跨工单不能撞 uuid
                m.getComments().add(new OnesComment("c-" + taskUuid, taskUuid, "u1", 1000L, "已修复"));
                m.getHistories().add(new OnesHistory(taskUuid, 2000L, "u1", "状态", "待办", "已处理"));
                return m;
            }
        };
    }

    private OnesTicket ticket(String uuid, int number, String customer) {
        OnesTicket t = new OnesTicket();
        t.setUuid(uuid);
        t.setNumber(number);
        t.setName("工单" + number);
        t.setStatusCategory("done");
        t.setCustomerUuid("c-" + customer);
        t.setCustomerName(customer);
        t.setCreateTimeMs(1793615397426L);
        t.setServerUpdateStampMs(1793615404029L);
        t.getProducts().add(new OnesRef("p1", "QMS"));
        return t;
    }

    /** 等待同步结束（最多 5 秒） */
    private void awaitSync(TicketSyncService service) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (service.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(100); // finally 中清进度状态
    }

    @Test
    void 首次手动同步走全量_二次走增量_水位推进() throws Exception {
        AtomicInteger fullCalls = new AtomicInteger();
        AtomicInteger incCalls = new AtomicInteger();
        TicketSyncService service = newService(fakeClient(fullCalls, incCalls));
        saveUserCookie(TEST_USER);

        assertTrue(service.startSync("manual", TEST_USER), "首次触发应成功");
        awaitSync(service);

        // 全量落库：工单 + 产品线 + 动态流 + 用户缓存
        assertEquals("工单100", jdbc.queryForObject(
                "SELECT name FROM ticket WHERE uuid = 't1'", String.class));
        assertEquals("QMS", jdbc.queryForObject(
                "SELECT product_name FROM ticket_product WHERE ticket_uuid = 't1'", String.class));
        assertEquals("张三", jdbc.queryForObject(
                "SELECT from_name FROM ticket_comment WHERE uuid = 'c-t1'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_history WHERE ticket_uuid = 't1'", Integer.class));
        assertEquals("张三", jdbc.queryForObject(
                "SELECT name FROM ones_user WHERE uuid = 'u1'", String.class));

        // 水位与成功时间推进，同步日志 done 且计数正确
        assertNotNull(store.getState(TicketSyncService.KEY_WATERMARK));
        assertNotNull(store.getState(TicketSyncService.KEY_LAST_SUCCESS));
        assertNull(store.getState(TicketSyncService.KEY_LAST_ERROR));
        Map<String, Object> logRow = store.recentSyncLogs(1).get(0);
        assertEquals("full", logRow.get("type"));
        assertEquals("manual", logRow.get("trigger_type"));
        assertEquals("done", logRow.get("status"));
        assertEquals(1, ((Number) logRow.get("ticket_count")).intValue());
        assertEquals(1, ((Number) logRow.get("comment_count")).intValue());

        // 有水位后触发走增量：写入 t2
        String watermark = store.getState(TicketSyncService.KEY_WATERMARK);
        service.startSync("manual", TEST_USER);
        awaitSync(service);
        assertEquals(1, incCalls.get(), "有水位应走增量拉取");
        assertEquals("工单200", jdbc.queryForObject(
                "SELECT name FROM ticket WHERE uuid = 't2'", String.class));
        assertTrue(store.recentSyncLogs(1).get(0).get("type").toString().equals("incremental"));

        // 幂等：同一批增量再跑一次，t2 动态流不产生重复数据
        service.startSync("manual", TEST_USER);
        awaitSync(service);
        assertEquals(2, incCalls.get(), "第二次触发仍走增量");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_comment WHERE ticket_uuid = 't2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_comment WHERE ticket_uuid = 't1'", Integer.class));

        assertNotNull(store.getState(TicketSyncService.KEY_WATERMARK));
        // 水位只推进不回退
        assertTrue(Long.parseLong(store.getState(TicketSyncService.KEY_WATERMARK)) >= Long.parseLong(watermark));
        assertEquals(1, fullCalls.get(), "全量拉取只应发生一次");
    }

    @Test
    void 同步失败_水位不动_失败原因可查_进度清空() throws Exception {
        OnesClient failing = new OnesClient() {
            @Override
            public List<OnesTicket> fetchTicketsCreatedSince(String cookie, long sinceMs) {
                throw new OnesClient.OnesAuthException("ONES Cookie 已失效，请到「个人中心 → 工单助手配置」更新");
            }
        };
        TicketSyncService service = newService(failing);
        saveUserCookie(TEST_USER);

        assertTrue(service.startSync("manual", TEST_USER));
        awaitSync(service);

        Map<String, Object> logRow = store.recentSyncLogs(1).get(0);
        assertEquals("failed", logRow.get("status"));
        assertTrue(String.valueOf(logRow.get("error")).contains("Cookie 已失效"));
        assertNull(store.getState(TicketSyncService.KEY_WATERMARK), "失败不应推进水位");
        assertTrue(String.valueOf(store.getState(TicketSyncService.KEY_LAST_ERROR)).contains("Cookie"));
        assertNull(store.getState(TicketSyncService.KEY_CURRENT), "结束后进度应清空");

        // 失败后再次触发仍可执行（running 标志复位）
        assertTrue(service.startSync("manual", TEST_USER));
        awaitSync(service);
    }

    @Test
    void 未配置Cookie时手动同步被拦截_记录失败日志并引导() throws Exception {
        TicketSyncService service = newService(
                fakeClient(new AtomicInteger(), new AtomicInteger()));

        assertTrue(service.startSync("manual", "无配置用户"));
        awaitSync(service);

        Map<String, Object> logRow = store.recentSyncLogs(1).get(0);
        assertEquals("failed", logRow.get("status"));
        assertTrue(String.valueOf(logRow.get("error")).contains("个人中心"), "失败原因应含引导文案");
        assertTrue(String.valueOf(store.getState(TicketSyncService.KEY_LAST_ERROR)).contains("ONES Cookie"));
        assertNull(store.getState(TicketSyncService.KEY_WATERMARK), "未拉取数据不应推进水位");
    }

    @Test
    void 无人配置Cookie时自动同步跳过_有人配置则用其Cookie执行() throws Exception {
        TicketSyncService service = newService(
                fakeClient(new AtomicInteger(), new AtomicInteger()));

        // 没有任何用户配置 Cookie：自动同步直接跳过，不产生同步日志
        assertTrue(service.startSync("auto", null));
        awaitSync(service);
        assertTrue(store.recentSyncLogs(20).isEmpty(), "跳过时不应产生同步日志");
        assertNull(store.getState(TicketSyncService.KEY_LAST_ERROR));

        // 任一用户配置后：自动同步用该用户的 Cookie 正常执行，并记住同步用户
        saveUserCookie(TEST_USER);
        assertTrue(service.startSync("auto", null));
        awaitSync(service);
        assertEquals("done", store.recentSyncLogs(1).get(0).get("status"));
        assertEquals(TEST_USER, store.getState(TicketSyncService.KEY_LAST_SYNC_USER));
        assertNotNull(store.getState(TicketSyncService.KEY_WATERMARK));
    }

    @Test
    void 状态快照包含最近日志与运行标志() throws Exception {
        TicketSyncService service = newService(fakeClient(new AtomicInteger(), new AtomicInteger()));
        saveUserCookie(TEST_USER);
        service.startSync("manual", TEST_USER);
        awaitSync(service);

        Map<String, Object> status = service.statusSnapshot();
        assertEquals(false, status.get("running"));
        assertNotNull(status.get("last_success_at"));
        assertNotNull(status.get("recent_logs"));
        assertEquals(1, ((List<?>) status.get("recent_logs")).size());
    }
}
