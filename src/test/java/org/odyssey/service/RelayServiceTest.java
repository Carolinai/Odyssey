package org.odyssey.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odyssey.config.OdysseyProperties;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中继服务测试：无代理在线拒绝、代理报到领任务、结果回传、令牌签发。
 */
class RelayServiceTest {

    @TempDir
    Path tempDir;

    private RelayService service() {
        OdysseyProperties props = new OdysseyProperties();
        props.setDataDir(tempDir.toString());
        return new RelayService(props);
    }

    @Test
    void 无代理在线_立即拒绝不空等() {
        RelayService service = service();
        long start = System.currentTimeMillis();
        try {
            service.execute("GET", "http://10.10.191.213/api", new HashMap<>(), null, 60_000);
            assertTrue(false, "无代理在线应抛出");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("中继代理不在线"));
        }
        assertTrue(System.currentTimeMillis() - start < 1000, "应立即失败而非等到超时");
    }

    @Test
    void 代理报到_领走任务_结果回传完成调用方() throws Exception {
        RelayService service = service();
        String token = service.issueToken("alice");
        assertNotNull(token);

        // 代理先报到一次（注册在线状态），调用方才不会被「不在线」立即拒绝
        service.poll(token, null);

        // 调用方线程发起一次中继请求
        CountDownLatch submitted = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<RelayService.RelayResult> got =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Exception> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                submitted.countDown();
                got.set(service.execute("GET", "http://10.10.191.213/api/test",
                        new HashMap<>(), null, 10_000));
            } catch (Exception e) {
                error.set(e);
            }
        });
        caller.start();
        submitted.await();
        Thread.sleep(300); // 等调用方完成入队（execute 的在线检查与 put）

        // 代理报到领任务
        java.util.List<Map<String, Object>> jobs = service.poll(token, null);
        assertEquals(1, jobs.size());
        assertEquals("GET", jobs.get(0).get("method"));
        assertEquals("http://10.10.191.213/api/test", jobs.get(0).get("url"));
        assertTrue(service.onlineAgents().contains("alice"));

        // 结果回传 → 调用方拿到响应
        Map<String, Object> result = new HashMap<>();
        result.put("id", jobs.get(0).get("id"));
        result.put("status", 200);
        result.put("headers", "");
        result.put("body", java.util.Base64.getEncoder().encodeToString("ok".getBytes()));
        service.poll(token, java.util.Collections.singletonList(result));

        caller.join(3000);
        assertNull(error.get());
        assertNotNull(got.get());
        assertEquals(200, got.get().status);
        assertEquals("ok", new String(got.get().body));
    }

    @Test
    void 未知令牌_报到被拒() {
        RelayService service = service();
        assertNull(service.poll("bad-token", null));
    }

    @Test
    void 重复登记用户_令牌复用() {
        RelayService service = service();
        String first = service.issueToken("alice");
        String second = service.issueToken("alice");
        assertEquals(first, second);
    }

    @Test
    void 中继线路地址_由http地址推导() {
        assertEquals("relay://10.10.191.213/project",
                OnesClient.relayBaseOf("http://10.10.191.213/project"));
        assertEquals("relay://124.70.159.198:8081/jenkins/",
                OnesClient.relayBaseOf("http://124.70.159.198:8081/jenkins/"));
    }
}
