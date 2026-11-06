package org.odyssey.service;

import org.junit.jupiter.api.Test;
import org.odyssey.config.OdysseyProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中继结果回传的响应头必须是解码后的明文。
 *
 * 背景：代理上报行是 RESULT\tid\tstatus\t<响应头b64>\t<响应体b64>。若 poll 只解码
 * body 不解码 headers，RelayConnection 读到的响应头是 Base64 乱码，Set-Cookie
 * 解析不出，Jenkins 会话存不进 COOKIE_STORE，提交脚本时回带不了会话 → 必 403
 * （Jenkins 给 POST 新建会话，crumb 绑定的是取 crumb 时 GET 的会话）。
 */
class RelayServiceResultHeadersTest {

    @Test
    void 结果回传的响应头应为解码后的明文() throws Exception {
        OdysseyProperties props = new OdysseyProperties();
        props.setDataDir(System.getProperty("java.io.tmpdir"));
        RelayService relay = new RelayService(props);
        String token = relay.issueToken("tester");

        // 代理报到（上线）
        relay.poll(token, null);

        // 异步提交一次代发请求
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RelayService.RelayResult> fut = pool.submit(
                    () -> relay.execute("GET", "http://upstream.invalid/api",
                            new LinkedHashMap<>(), new byte[0], 10_000));

            // 轮询领走任务（等待任务入队），与代理行为一致
            Map<String, Object> job = null;
            for (int i = 0; i < 100 && job == null; i++) {
                List<Map<String, Object>> jobs = relay.poll(token, null);
                if (!jobs.isEmpty()) {
                    job = jobs.get(0);
                } else {
                    Thread.sleep(20);
                }
            }
            assertEquals(true, job != null, "任务应被领取");

            // 模拟代理回传：headers/body 均为 Base64（与 RelayAgentMain.resultLine 一致）
            String plainHeaders = "Set-Cookie: JSESSIONID=test123; Path=/\nContent-Type: text/html";
            Map<String, Object> result = new HashMap<>();
            result.put("id", job.get("id"));
            result.put("status", 200);
            result.put("headers", Base64.getEncoder()
                    .encodeToString(plainHeaders.getBytes(StandardCharsets.UTF_8)));
            result.put("body", Base64.getEncoder()
                    .encodeToString("ok".getBytes(StandardCharsets.UTF_8)));
            result.put("encoding", "");
            relay.poll(token, java.util.Collections.singletonList(result));

            RelayService.RelayResult r = fut.get();
            assertEquals(200, r.status);
            assertTrue(r.headers.contains("Set-Cookie: JSESSIONID=test123"),
                    "响应头应为解码后的明文，实际是: " + r.headers);
        } finally {
            pool.shutdownNow();
        }
    }
}
