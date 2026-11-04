package org.odyssey.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odyssey.model.UserConfig;
import org.odyssey.store.UserConfigStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 网关客户端测试：JDK 内置 HttpServer 起本地假网关，覆盖
 * thinking 块过滤、max_tokens 续写拼接、失败重试、密钥无效与配置缺失的异常区分、调用限速。
 */
class AiGatewayClientTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private UserConfigStore configStore;
    private AiGatewayClient client;
    private final AtomicInteger hits = new AtomicInteger();

    /** 测试用的触发用户（AI 配置按其个人配置解析） */
    private static final String TEST_USER = "tester";

    @BeforeEach
    void setUp() {
        // 个人配置存储是 <dataDir>/users/<user>.yaml 文件，不依赖数据库；临时目录即隔离
        configStore = new UserConfigStore(tempDir);
        client = new AiGatewayClient(configStore);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 起假网关：entries 依次是 "状态码:响应体"（如 "200:{...}"），最后一个复用。
     * intervalMs 为调用间隔配置（0 = 不限速）。
     */
    private void startGateway(int intervalMs, String... entries) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger idx = new AtomicInteger();
        server.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            String[] parts = entries[Math.min(idx.getAndIncrement(), entries.length - 1)].split(":", 2);
            int status = Integer.parseInt(parts[0]);
            byte[] body = parts[1].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        UserConfig c = new UserConfig();
        c.setAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        c.setAiApiKey("test-key");
        c.setAiModel("glm-test");
        c.setAiCallIntervalMs(intervalMs);
        c.setAiMaxRetries(2);
        configStore.save(TEST_USER, c);
    }

    /** 模拟网关真实返回：先 thinking 块再 text 块（glm-5.3-flash 实测行为） */
    private static String reply(String text, String stopReason) {
        return "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"让我想想\"},"
                + "{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                + "\"stop_reason\":\"" + stopReason + "\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
    }

    @Test
    void chatFiltersThinkingAndReturnsText() throws IOException {
        startGateway(0, "200:" + reply("你好，世界", "end_turn"));
        assertEquals("你好，世界", client.chat(TEST_USER, "sys", "问题", 1024));
        assertEquals(1, hits.get());
    }

    @Test
    void chatContinuesAfterMaxTokensTruncation() throws IOException {
        // 第一轮被截断，第二轮结束：正文 = 两轮 text 拼接（thinking 不混入）
        startGateway(0, "200:" + reply("第一段", "max_tokens"), "200:" + reply("第二段", "end_turn"));
        assertEquals("第一段第二段", client.chat(TEST_USER, null, "写长文", 64));
        assertEquals(2, hits.get());
    }

    @Test
    void retryOnServerErrorThenSuccess() throws IOException {
        startGateway(0, "500:{\"error\":{\"message\":\"网关内部错误\"}}", "200:" + reply("重试成功", "end_turn"));
        assertEquals("重试成功", client.chat(TEST_USER, null, "问题", 64));
        assertEquals(2, hits.get());
    }

    @Test
    void invalidKeyThrowsConfigException() throws IOException {
        startGateway(0, "401:{\"error\":{\"message\":\"unauthorized\"}}");
        AiGatewayClient.AiConfigException e = assertThrows(AiGatewayClient.AiConfigException.class,
                () -> client.chat(TEST_USER, null, "问题", 64));
        assertTrue(e.getMessage().contains("密钥无效"));
    }

    @Test
    void missingConfigThrowsIdentifiableException() {
        AiGatewayClient.AiConfigException e = assertThrows(AiGatewayClient.AiConfigException.class,
                () -> client.chat(TEST_USER, "sys", "问题", 64));
        assertTrue(e.getMessage().contains("AI 配置不完整"));
    }

    @Test
    void callIntervalThrottlesBatchCalls() throws IOException {
        // 间隔 300ms 连发 3 次：总耗时至少 2×300ms（第三次立即发起）
        startGateway(300, "200:" + reply("ok", "end_turn"));
        long start = System.currentTimeMillis();
        client.chat(TEST_USER, null, "a", 16);
        client.chat(TEST_USER, null, "b", 16);
        client.chat(TEST_USER, null, "c", 16);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 600, "三次调用应至少间隔 2×300ms，实际 " + elapsed + "ms");
    }
}
