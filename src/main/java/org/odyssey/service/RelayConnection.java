package org.odyssey.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经 {@link RelayService} 代发的 HttpURLConnection 适配器。
 *
 * 三个上游客户端（OnesClient/JenkinsService/AiGatewayClient）的调用逻辑全部基于
 * HttpURLConnection（setRequestProperty → getOutputStream → getResponseCode →
 * getInputStream）。本类实现同一接口，内部把请求转交中继队列，客户端代码
 * 只需把 {@code new URL(spec).openConnection()} 换成 {@link #open(spec)}
 * 即可接入中继线路，调用逻辑零改动。
 *
 * URL 形如 {@code relay://<目标主机[:端口]>/路径}，例如
 * {@code relay://10.10.191.213:80/api/...}——协议头后的部分去掉 relay:// 后补上
 * http:// 前缀即代理要访问的目标 URL（三个上游均为 http，https 需扩展本适配器）。
 */
public class RelayConnection extends HttpURLConnection {

    /** 中继执行超时：取读超时加余量（排队与代理轮询间隔的开销） */
    private static final long RELAY_OVERHEAD_MS = 10_000;

    private final String targetUrl;
    private final Map<String, String> requestHeaders = new LinkedHashMap<>();
    private final ByteArrayOutputStream sentBody = new ByteArrayOutputStream();
    private RelayService.RelayResult response;
    private long readTimeoutMs = 60_000;

    RelayConnection(String spec) {
        super(ofRelay(spec));
        // relay://10.10.191.213:80/api/... → http://10.10.191.213:80/api/...
        this.targetUrl = "http://" + spec.substring("relay://".length());
    }

    /**
     * 中继线路的 Cookie 存续：HttpURLConnection 直连时靠 JVM 级 CookieHandler 自动
     * 保持会话（Jenkins crumb 与脚本提交必须同会话），中继适配器则自行记录 Set-Cookie
     * 并在后续请求回带。键为「目标主机（host:port）+ 用户（Authorization）」——
     * Jenkins 的 crumb 绑定 Web 会话，多用户共用会话会互相覆盖导致 403。
     */
    private static final Map<String, Map<String, String>> COOKIE_STORE = new java.util.concurrent.ConcurrentHashMap<>();

    private String cookieHost() {
        try {
            java.net.URI u = java.net.URI.create(targetUrl);
            // 会话按用户隔离：Jenkins 的 crumb 与 Web 会话绑定，多用户共用一个会话时
            // 后取 crumb 的用户会覆盖前者会话，导致前者提交脚本 403（No valid crumb）。
            // 以 Authorization 区分用户（各请求方都显式携带）。
            String user = requestHeaders.getOrDefault("Authorization", "");
            return u.getHost() + ":" + (u.getPort() > 0 ? u.getPort() : 80) + "|" + user;
        } catch (Exception e) {
            return targetUrl;
        }
    }

    private static URL ofRelay(String spec) {
        try {
            // URL 对象仅用于父类的 url 字段展示，协议头非 http 不会真实连接
            return new URL("http", "relay.invalid", 80, "/");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 统一的连接入口：relay:// 开头走中继适配器，其余原样直连。
     * 各客户端把 {@code new URL(spec).openConnection()} 替换为本方法即可。
     */
    public static HttpURLConnection open(String spec) throws IOException {
        if (spec.startsWith("relay://")) {
            return new RelayConnection(spec);
        }
        return (HttpURLConnection) new URL(spec).openConnection();
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
        this.method = method;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        requestHeaders.put(key, value);
    }

    @Override
    public void setReadTimeout(int timeout) {
        super.setReadTimeout(timeout);
        readTimeoutMs = timeout;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return sentBody;
    }

    @Override
    public int getResponseCode() throws IOException {
        connect();
        return response.status;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        if (response.status >= 400) {
            throw new java.io.FileNotFoundException("HTTP " + response.status);
        }
        return new ByteArrayInputStream(response.body);
    }

    @Override
    public InputStream getErrorStream() {
        if (response == null || response.status < 400) {
            return null;
        }
        return new ByteArrayInputStream(response.body);
    }

    @Override
    public String getHeaderField(String name) {
        // 中继响应头按「名称: 值」行序保存，取第一个匹配（与直连行为一致）
        if (response == null || response.headers == null) {
            return null;
        }
        for (String line : response.headers.split("\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && name.equalsIgnoreCase(line.substring(0, idx).trim())) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    @Override
    public void connect() throws IOException {
        if (connected) {
            return;
        }
        RelayService relay = SpringHolder.relay();
        if (relay == null) {
            throw new IOException("中继服务不可用");
        }
        // 回带该目标主机历史会话的 Cookie（请求方已显式设置 Cookie 时不覆盖）
        Map<String, String> cookies = COOKIE_STORE.get(cookieHost());
        Map<String, String> headers = new LinkedHashMap<>(requestHeaders);
        if (cookies != null && !cookies.isEmpty() && !headers.containsKey("Cookie")) {
            StringBuilder ck = new StringBuilder();
            for (Map.Entry<String, String> c : cookies.entrySet()) {
                if (ck.length() > 0) {
                    ck.append("; ");
                }
                ck.append(c.getKey()).append('=').append(c.getValue());
            }
            headers.put("Cookie", ck.toString());
        }
        long timeout = readTimeoutMs + RELAY_OVERHEAD_MS;
        response = relay.execute(method, targetUrl, headers,
                sentBody.toByteArray(), timeout);
        storeSetCookies(response.headers);
        connected = true;
    }

    /** 从响应头提取 Set-Cookie 更新该目标主机的会话（值内含Expires等属性段丢弃） */
    private void storeSetCookies(String responseHeaders) {
        if (responseHeaders == null || responseHeaders.isEmpty()) {
            return;
        }
        for (String line : responseHeaders.split("\n")) {
            int idx = line.indexOf(':');
            if (idx <= 0 || !"Set-Cookie".equalsIgnoreCase(line.substring(0, idx).trim())) {
                continue;
            }
            String pair = line.substring(idx + 1).trim();
            int end = pair.indexOf(';');
            String kv = end < 0 ? pair : pair.substring(0, end);
            int eq = kv.indexOf('=');
            if (eq > 0) {
                Map<String, String> cookies = COOKIE_STORE.get(cookieHost());
                if (cookies == null) {
                    cookies = new LinkedHashMap<>();
                    COOKIE_STORE.put(cookieHost(), cookies);
                }
                cookies.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public boolean usingProxy() {
        return true;
    }

    /**
     * 中继连接不在 Servlet 线程里构造（都在请求处理中），Spring 上下文经静态桥获取
     * RelayService：适配器要覆盖「登录前校验」等不经过 Spring 注入的调用点。
     */
    static class SpringHolder {
        static volatile RelayService relayService;

        static RelayService relay() {
            return relayService;
        }
    }

    String targetUrl() {
        return targetUrl;
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : requestHeaders.entrySet()) {
            result.put(e.getKey(), new java.util.ArrayList<>(java.util.Collections.singletonList(e.getValue())));
        }
        return result;
    }
}
