package org.odyssey.service;

import org.junit.jupiter.api.Test;
import org.odyssey.config.OdysseyProperties;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中继连接的会话 Cookie 按用户隔离测试。
 *
 * 背景：Jenkins 的 CSRF crumb 与 Web 会话（JSESSIONID）绑定。中继线路的
 * Cookie 存储若按「目标主机」全局共享，多用户交替使用时后一个用户取 crumb
 * 会覆盖前一个用户的会话，导致前一个用户提交脚本时 crumb 与会话错配
 * （Jenkins 返回 403 No valid crumb）。Cookie 必须按用户（Authorization）隔离。
 */
class RelayConnectionTest {

    /** 伪造中继服务：记录每次请求头，按预设依次返回不同的 Set-Cookie 会话 */
    static class FakeRelay extends RelayService {
        final List<Map<String, String>> seenHeaders = new CopyOnWriteArrayList<>();
        private final java.util.Iterator<String> sessionIds;

        FakeRelay(java.util.List<String> sessionIds) {
            super(props());
            this.sessionIds = sessionIds.iterator();
        }

        private static OdysseyProperties props() {
            OdysseyProperties p = new OdysseyProperties();
            p.setDataDir(System.getProperty("java.io.tmpdir"));
            return p;
        }

        @Override
        public RelayResult execute(String method, String url, Map<String, String> headers,
                byte[] body, long timeoutMs) {
            seenHeaders.add(new LinkedHashMap<>(headers));
            String cookie = "Set-Cookie: JSESSIONID=" + sessionIds.next();
            return new RelayResult(200, cookie + "\n", "ok".getBytes());
        }
    }

    private static String authOf(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":pw").getBytes());
    }

    @Test
    void 多用户交替请求_会话Cookie按用户隔离() throws Exception {
        // 同一 Jenkins 主机：A 建会话 sesA，B 建会话 sesB，随后 A 再提交请求
        FakeRelay fake = new FakeRelay(java.util.Arrays.asList("sesA", "sesB", "sesA2"));
        RelayConnection.SpringHolder.relayService = fake;
        try {
            String base = "relay://jenkins-cookie-test.invalid:8080/jk";
            String userA = authOf("alice");
            String userB = authOf("bob");

            // A 取 crumb（建立会话 sesA）
            RelayConnection c1 = new RelayConnection(base + "/crumbIssuer/api/json");
            c1.setRequestMethod("GET");
            c1.setRequestProperty("Authorization", userA);
            c1.getResponseCode();

            // B 取 crumb（建立会话 sesB，历史上会覆盖全局存储里 A 的会话）
            RelayConnection c2 = new RelayConnection(base + "/crumbIssuer/api/json");
            c2.setRequestMethod("GET");
            c2.setRequestProperty("Authorization", userB);
            c2.getResponseCode();

            // A 提交脚本：必须仍带 A 自己的会话 sesA
            RelayConnection c3 = new RelayConnection(base + "/script");
            c3.setRequestMethod("POST");
            c3.setRequestProperty("Authorization", userA);
            c3.getResponseCode();

            // 中继实际收到 A 的第三次请求时应回带 sesA（而不是被 B 覆盖后的 sesB）
            Map<String, String> third = fake.seenHeaders.get(2);
            assertNotNull(third.get("Cookie"), "第三次请求应回带会话 Cookie");
            assertTrue(third.get("Cookie").contains("sesA"),
                    "A 的请求应带 A 自己的会话，实际带的是: " + third.get("Cookie"));
            assertEquals(-1, third.get("Cookie").indexOf("sesB"), "不应混入 B 的会话");
        } finally {
            RelayConnection.SpringHolder.relayService = null;
        }
    }
}
