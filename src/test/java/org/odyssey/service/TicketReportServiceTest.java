package org.odyssey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odyssey.model.UserConfig;
import org.odyssey.store.TaskStore;
import org.odyssey.store.TicketReportStore;
import org.odyssey.store.TicketSchemaInitializer;
import org.odyssey.store.TicketStatsStore;
import org.odyssey.store.UserConfigStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 报告生成服务测试：真实 SQLite + 本地假网关，覆盖
 * 章节组装、簇工单号校验（幻觉号剔除）、簇间归并、空范围失败与 AI 失败落库。
 */
class TicketReportServiceTest {

    @TempDir
    Path tempDir;

    private static final String TEST_USER = "tester";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private JdbcTemplate jdbc;
    private TicketReportStore reportStore;
    private TicketReportService service;
    private TaskExecutor.TaskContext ctx;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("odyssey.db"));
        jdbc = new JdbcTemplate(ds);
        new TicketSchemaInitializer(jdbc).run(null);
        reportStore = new TicketReportStore(jdbc);
        TicketStatsStore statsStore = new TicketStatsStore(jdbc);

        // 个人配置指向假网关（限速 0：测试不限速）
        UserConfigStore configStore = new UserConfigStore(tempDir);
        UserConfig c = new UserConfig();
        c.setAiBaseUrl("http://127.0.0.1:1/"); // startGateway 里会改成真实端口后重存
        c.setAiApiKey("test-key");
        c.setAiModel("glm-test");
        c.setAiCallIntervalMs(0);
        c.setAiMaxRetries(1);
        configStore.save(TEST_USER, c);
        AiGatewayClient ai = new AiGatewayClient(configStore);

        service = new TicketReportService(statsStore, reportStore, ai);
        // TaskContext 仅用于进度上报，包级构造；id=1 无对应行时更新是空操作
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

    /** 3 张夹具工单：101/102 登录类（QMS）、103 导出类（TMS），用于簇归并断言 */
    private void insertTickets() {
        insertTicket("t1", 101, "登录失败", "登录时提示密码错误，重试多次无效", "正大天晴", "QMS");
        insertTicket("t2", 102, "登录超时", "登录页面一直转圈超时", "澳斯康", "QMS");
        insertTicket("t3", 103, "导出报错", "导出报表时提示系统错误", "甘李", "TMS");
    }

    private void insertTicket(String uuid, int number, String name, String desc,
                              String customer, String product) {
        jdbc.update("INSERT INTO ticket(uuid, number, name, description_text, status_category,"
                        + " status_name, priority, assign_uuid, assign_name, customer_uuid, customer_name,"
                        + " create_time, server_update_stamp, synced_at)"
                        + " VALUES (?, ?, ?, ?, 'done', '已解决', '中', 'u1', '张三', ?, ?, ?, ?, ?)",
                uuid, number, name, desc, customer + "-uuid", customer,
                System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis());
        jdbc.update("INSERT INTO ticket_product(ticket_uuid, product_name) VALUES (?, ?)", uuid, product);
    }

    /** 起假网关：entries 依次是 "状态码:响应体"（同 AiGatewayClientTest），最后一个复用 */
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

        // 配置的 Base URL 依赖端口，起完服务后再存一次
        UserConfigStore configStore = new UserConfigStore(tempDir);
        UserConfig c = new UserConfig();
        c.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        c.setAiApiKey("test-key");
        c.setAiModel("glm-test");
        c.setAiCallIntervalMs(0);
        c.setAiMaxRetries(1);
        configStore.save(TEST_USER, c);
    }

    /** Anthropic 格式响应（仅 text 块）：经 Jackson 序列化避免手工转义 */
    private static String reply(String text) throws Exception {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<Map<String, String>> content = new ArrayList<>();
        Map<String, String> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        content.add(block);
        resp.put("content", content);
        resp.put("stop_reason", "end_turn");
        Map<String, Integer> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 10);
        usage.put("output_tokens", 20);
        resp.put("usage", usage);
        return MAPPER.writeValueAsString(resp);
    }

    private static TicketStatsStore.Filter allFilter() {
        return TicketStatsStore.Filter.of(null, null, null, null, null, null, null, null, null);
    }

    private static Map<String, Object> defaultParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", null);
        params.put("to", null);
        params.put("product", null);
        params.put("customer", null);
        params.put("status_name", null);
        return params;
    }

    @Test
    void generatesFullReportWithMergedClusters() throws Exception {
        // 调用顺序：总结 → 维度 → 建议 → 分批聚类（3 张一批）→ 簇间归并
        startGateway(
                "200:" + reply("## 工单情况总结\n本期共 3 单。"),
                "200:" + reply("## 维度分析\nQMS 占三分之二。"),
                "200:" + reply("## 优化建议\n### 项目优化建议\n加强登录模块排查。"),
                // 批次聚类：一个真实簇 + 一个含幻觉工单号（999）的簇（应被剔除）
                "200:" + reply("```json\n[{\"topic\":\"登录类\",\"numbers\":[101,102],"
                        + "\"common\":\"登录异常\"},{\"topic\":\"幻觉簇\",\"numbers\":[999]}]\n```"),
                // 归并：合并为 3 单的簇，numbers 混用字符串与数字写法
                "200:" + reply("[{\"topic\":\"登录与导出问题\",\"numbers\":[\"#101\",\"#102\",103],"
                        + "\"common\":\"客户端异常提示不清\"}]"));

        service.run(ctx, TEST_USER, allFilter(), defaultParams());

        assertEquals(5, hits.get());
        List<Map<String, Object>> list = reportStore.list(10);
        assertEquals(1, list.size());
        assertEquals("done", list.get(0).get("status"));

        long reportId = ((Number) list.get(0).get("id")).longValue();
        String md = (String) reportStore.get(reportId).get("content_md");
        // 三个章节齐全
        assertTrue(md.contains("## 工单情况总结"));
        assertTrue(md.contains("## 维度分析"));
        assertTrue(md.contains("## 优化建议"));
        // Meta 头：默认近六个月 + 工单量
        assertTrue(md.contains("近六个月"));
        assertTrue(md.contains("工单量：3 张"));
        // 相似簇：归并后主题、全部真实工单号、共性问题
        assertTrue(md.contains("### 登录与导出问题（3 单）"));
        assertTrue(md.contains("#101"));
        assertTrue(md.contains("#103"));
        assertTrue(md.contains("客户端异常提示不清"));
        // 幻觉工单号不出现
        assertFalse(md.contains("999"));
    }

    @Test
    void emptyRangeFailsWithReason() throws Exception {
        startGateway("200:" + reply("不应被调用"));
        TicketStatsStore.Filter empty = TicketStatsStore.Filter.of(
                null, null, "不存在的产品", null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> service.run(ctx, TEST_USER, empty, defaultParams()));

        List<Map<String, Object>> list = reportStore.list(10);
        assertEquals(1, list.size());
        assertEquals("failed", list.get(0).get("status"));
        assertTrue(String.valueOf(list.get(0).get("error")).contains("没有工单"));
        assertEquals(0, hits.get());
    }

    @Test
    void aiFailureMarksReportFailed() throws Exception {
        startGateway("401:{\"error\":{\"message\":\"unauthorized\"}}");
        assertThrows(AiGatewayClient.AiConfigException.class,
                () -> service.run(ctx, TEST_USER, allFilter(), defaultParams()));

        List<Map<String, Object>> list = reportStore.list(10);
        assertEquals(1, list.size());
        assertEquals("failed", list.get(0).get("status"));
        assertTrue(String.valueOf(list.get(0).get("error")).contains("密钥无效"));
    }
}
