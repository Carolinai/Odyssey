package org.odyssey.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.odyssey.config.JenkinsProperties;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JenkinsService 直连/隧道线路的会话 Cookie 按用户隔离测试。
 *
 * 背景：Jenkins 的 CSRF crumb 与 Web 会话（JSESSIONID）绑定。直连线路靠 JVM 级
 * 全局 CookieHandler 保存会话，多用户交替使用时后一个用户的请求会覆盖共享会话，
 * 前一个用户「取完 crumb → 提交脚本」的窗口期内会话被换掉，提交即 403
 * （No valid crumb）。会话必须按用户隔离。
 */
class JenkinsServiceSessionTest {

    /** 从请求头解析 JSESSIONID（无则返回 null），模拟 Jenkins 的会话识别 */
    private static String sessionOf(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return null;
        }
        for (String part : cookie.split(";")) {
            String kv = part.trim();
            if (kv.startsWith("JSESSIONID=")) {
                return kv.substring("JSESSIONID=".length());
            }
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String text) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * 伪造 Jenkins：crumb 与会话绑定；会话归属首个以认证身份创建它的用户，
     * 其他用户带此会话访问时按会话固定防护重新生成会话（生产上观察到的行为）。
     */
    private static HttpServer fakeJenkins(Map<String, String> crumbBySession,
            Map<String, String> ownerBySession, AtomicInteger sessionSeq) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/crumbIssuer/api/json", exchange -> {
            String user = userOf(exchange);
            String session = sessionOf(exchange);
            if (session == null || !user.equals(ownerBySession.get(session))) {
                session = "s" + sessionSeq.incrementAndGet();
                ownerBySession.put(session, user);
            }
            crumbBySession.putIfAbsent(session, "crumb-" + session);
            exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=" + session + "; Path=/");
            respond(exchange, 200, "{\"crumb\":\"" + crumbBySession.get(session) + "\"}");
        });
        server.createContext("/script", exchange -> {
            String session = sessionOf(exchange);
            String crumb = exchange.getRequestHeaders().getFirst("Jenkins-Crumb");
            boolean valid = session != null && crumb != null
                    && crumb.equals(crumbBySession.get(session));
            if (!valid) {
                respond(exchange, 403, "Error 403 No valid crumb was included in the request");
            } else {
                respond(exchange, 200, "<pre>VIEW|demo|备注</pre>");
            }
        });
        server.start();
        return server;
    }

    private static String userOf(HttpExchange exchange) {
        // Authorization: Basic base64(user:pw)，测试里用户名即 user
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) {
            return "anonymous";
        }
        String decoded = new String(java.util.Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        return colon < 0 ? decoded : decoded.substring(0, colon);
    }

    @Test
    void 单用户连续调用_会话复用不换新() throws Exception {
        Map<String, String> crumbBySession = new ConcurrentHashMap<>();
        Map<String, String> ownerBySession = new ConcurrentHashMap<>();
        AtomicInteger sessionSeq = new AtomicInteger();
        HttpServer server = fakeJenkins(crumbBySession, ownerBySession, sessionSeq);
        try {
            JenkinsService service = service(server);
            for (int i = 0; i < 3; i++) {
                List<JenkinsService.ViewInfo> views = service.fetchViews("alice", "pw");
                assertEquals(1, views.size());
                assertEquals("demo", views.get(0).getName());
            }
            // 连续调用应始终复用同一个会话
            assertEquals(1, ownerBySession.size(), "单用户连续调用不应创建多个会话");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 多用户并发交替_各自会话独立互不影响() throws Exception {
        Map<String, String> crumbBySession = new ConcurrentHashMap<>();
        Map<String, String> ownerBySession = new ConcurrentHashMap<>();
        AtomicInteger sessionSeq = new AtomicInteger();
        HttpServer server = fakeJenkins(crumbBySession, ownerBySession, sessionSeq);
        try {
            JenkinsService service = service(server);
            // 两个用户真并发交替调用：会话按用户隔离后，任何交错都互不影响。
            // （修复前全局共享会话时，此场景会概率性 403——已在旧代码上复现验证过）
            AtomicReference<List<JenkinsService.ViewInfo>> aliceViews = new AtomicReference<>();
            AtomicReference<Exception> aliceError = new AtomicReference<>();
            AtomicReference<List<JenkinsService.ViewInfo>> bobViews = new AtomicReference<>();
            AtomicReference<Exception> bobError = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            Thread threadA = new Thread(() -> {
                try {
                    start.await();
                    aliceViews.set(service.fetchViews("alice", "pw"));
                } catch (Exception e) {
                    aliceError.set(e);
                }
            });
            Thread threadB = new Thread(() -> {
                try {
                    start.await();
                    bobViews.set(service.fetchViews("bob", "pw"));
                } catch (Exception e) {
                    bobError.set(e);
                }
            });
            threadA.start();
            threadB.start();
            start.countDown();
            threadA.join(15_000);
            threadB.join(15_000);

            assertNull(aliceError.get(), "A 的视图查询不应失败");
            assertNull(bobError.get(), "B 的视图查询不应失败");
            assertEquals(1, aliceViews.get().size());
            assertEquals(1, bobViews.get().size());
            // 每个用户各自持有独立会话
            assertEquals(2, ownerBySession.size(), "两个用户应有各自独立的会话");
        } finally {
            server.stop(0);
        }
    }

    private static JenkinsService service(HttpServer server) {
        JenkinsProperties props = new JenkinsProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new JenkinsService(props);
    }
}
