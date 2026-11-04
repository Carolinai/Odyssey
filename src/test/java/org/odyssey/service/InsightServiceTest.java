package org.odyssey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odyssey.model.UserConfig;
import org.odyssey.store.InsightStore;
import org.odyssey.store.TaskStore;
import org.odyssey.store.TicketSchemaInitializer;
import org.odyssey.store.UserConfigStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 经验提炼服务测试：真实 SQLite + 本地假网关，覆盖
 * 待提炼队列消费、幂等跳过、语料带上评论、单张失败不中断、指定工单增量提炼。
 */
class InsightServiceTest {

    @TempDir
    Path tempDir;

    private static final String TEST_USER = "tester";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private JdbcTemplate jdbc;
    private InsightStore store;
    private InsightService service;
    private TaskExecutor.TaskContext ctx;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("odyssey.db"));
        jdbc = new JdbcTemplate(ds);
        new TicketSchemaInitializer(jdbc).run(null);
        store = new InsightStore(jdbc);
        service = new InsightService(store, new AiGatewayClient(new UserConfigStore(tempDir)),
                new UserConfigStore(tempDir));
        TaskExecutor taskExecutor = new TaskExecutor(new TaskStore(jdbc));
        ctx = taskExecutor.new TaskContext(1);
        insertTickets();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 3 张已解决工单（t1 已有经验）+ t2 一条评论 */
    private void insertTickets() {
        insertTicket("t1", 101, "登录失败", "密码错误提示", "正大天晴", "QMS");
        insertTicket("t2", 102, "导出报错", "导出失败", "澳斯康", "TMS");
        insertTicket("t3", 103, "打印格式错", "格式偏移", "甘李", "QMS");
        jdbc.update("INSERT INTO ticket_comment(uuid, ticket_uuid, from_name, send_time, text)"
                + " VALUES ('c1', 't2', '王五', 1, '重启服务后恢复')");
        store.upsert("t1", "正大天晴", "QMS", "已有经验", "glm-test");
    }

    private void insertTicket(String uuid, int number, String name, String desc, String customer, String product) {
        jdbc.update("INSERT INTO ticket(uuid, number, name, description_text, status_category,"
                        + " status_name, priority, assign_uuid, assign_name, customer_uuid, customer_name,"
                        + " create_time, server_update_stamp, synced_at)"
                        + " VALUES (?, ?, ?, ?, 'done', '已解决', '中', 'u1', '张三', ?, ?, ?, ?, ?)",
                uuid, number, name, desc, customer + "-uuid", customer,
                System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis());
        jdbc.update("INSERT INTO ticket_product(ticket_uuid, product_name) VALUES (?, ?)", uuid, product);
    }

    /** 起假网关并保存个人 AI 配置（entries 同 AiGatewayClientTest） */
    private void startGateway(String... entries) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger idx = new AtomicInteger();
        server.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            String[] parts = entries[Math.min(idx.getAndIncrement(), entries.length - 1)].split(":", 2);
            byte[] body = parts[1].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(Integer.parseInt(parts[0]), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        UserConfigStore configStore = new UserConfigStore(tempDir);
        UserConfig c = new UserConfig();
        c.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        c.setAiApiKey("test-key");
        c.setAiModel("glm-test");
        c.setAiCallIntervalMs(0);
        c.setAiMaxRetries(1);
        configStore.save(TEST_USER, c);
    }

    /** Anthropic 格式响应（仅 text 块），Jackson 序列化避免手工转义 */
    private static String reply(String text) throws Exception {
        Map<String, Object> resp = new LinkedHashMap<>();
        Map<String, String> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        resp.put("content", List.of(block));
        resp.put("stop_reason", "end_turn");
        Map<String, Integer> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 10);
        usage.put("output_tokens", 20);
        resp.put("usage", usage);
        return MAPPER.writeValueAsString(resp);
    }

    @Test
    void extractsPendingTicketsWithComments() throws Exception {
        startGateway("200:" + reply("## 问题现象\n现象\n## 原因分析\n原因\n## 解决方式\n方式"));

        int ok = service.run(ctx, TEST_USER, null, null);
        assertEquals(2, ok);   // t2/t3（t1 已有经验跳过）
        assertEquals(2, hits.get());

        // 语料应带上评论（假网关收到的最后一个请求体里包含评论人）
        // 经验落库校验：customer/product 取自工单，正文与模型名入库
        Map<String, Object> insight = jdbc.queryForList(
                "SELECT * FROM insight WHERE ticket_uuid = 't2'").get(0);
        assertEquals("澳斯康", insight.get("customer_name"));
        assertEquals("TMS", insight.get("product_name"));
        assertEquals("glm-test", insight.get("model"));
        assertTrue(String.valueOf(insight.get("content_md")).contains("## 解决方式"));
    }

    @Test
    void singleFailureDoesNotAbortBatch() throws Exception {
        // 待提炼队列按工单号倒序（t3 在前）：第 1 次调用 401（t3 失败不入库），之后成功（t2）
        startGateway("401:{\"error\":{\"message\":\"unauthorized\"}}",
                "200:" + reply("## 解决方式\n方式"));
        int ok = service.run(ctx, TEST_USER, null, null);
        assertEquals(1, ok);
        // 失败的不入库：只有 t2 有新经验，t1 的旧经验不受影响
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM insight", Long.class).intValue());
        assertNull(jdbc.query("SELECT content_md FROM insight WHERE ticket_uuid = 't3'",
                rs -> rs.next() ? rs.getString(1) : null));
    }

    @Test
    void onlyUuidsLimitsScope() throws Exception {
        startGateway("200:" + reply("## 解决方式\n方式"));
        int ok = service.run(ctx, TEST_USER, Arrays.asList("t2"), null);
        assertEquals(1, ok);
        assertEquals(1, hits.get());
        // 只提炼了 t2，t3 仍在待提炼队列
        assertEquals(1, store.pendingTickets(10).size());
        assertEquals("t3", store.pendingTickets(10).get(0).get("uuid"));
    }

    @Test
    void stopRequestEndsBeforeNextTicket() throws Exception {
        startGateway("200:" + reply("## 解决方式\n方式"));
        // 开跑前就请求终止（协作式停在第一张开始前）：0 张提炼、0 次调用
        service.requestStop(ctx.taskId());
        int ok = service.run(ctx, TEST_USER, null, null);
        assertEquals(0, ok);
        assertEquals(0, hits.get());
        // 待提炼队列原封不动，下次正常跑
        assertEquals(2, store.pendingTickets(10).size());
    }
}
