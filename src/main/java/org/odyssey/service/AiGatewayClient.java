package org.odyssey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.odyssey.model.UserConfig;
import org.odyssey.store.UserConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 网关客户端（Anthropic Messages 协议，配置为触发用户的个人配置：
 * AI 三项与三个调用参数，见 UserConfig）。
 *
 * 网关行为（2026-11-02 实测）：glm-5.3-flash 会先输出 thinking 块再输出 text 块——
 * 解析正文必须按 type=text 过滤，且 max_tokens 要给足，否则全消耗在思考上、正文为空。
 *
 * 正式调用 {@link #chat(String, String, String, int)}：每次调用前现读该用户的个人配置（热更新），
 * 带调用间隔限速、超时与重试（仅网络异常/5xx/429），stop_reason=max_tokens 时自动续写拼接。
 *
 * 多线路（同 OnesClient 模式）：服务器直连网关可能被 IP 白名单拒绝（HTTP 403），
 * 此时自动换走用户隧道线路（TunnelService 第三条转发 127.0.0.{槽位}:18083），
 * 并把 Host 头写回真实域名（网关按 Host 路由；需 JVM 参数
 * -Dsun.net.http.allowRestrictedHeaders=true，见 OdysseyApplication）。
 */
@Service
public class AiGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayClient.class);

    /** 缺省值：调用间隔 1s / 读超时 3min / 重试 2 次；续写最多 8 轮防死循环 */
    private static final int DEF_INTERVAL_MS = 1000;
    private static final int DEF_TIMEOUT_MS = 180_000;
    private static final int DEF_MAX_RETRIES = 2;
    private static final int MAX_CONTINUATIONS = 8;
    private static final long RETRY_BACKOFF_MS = 2000;

    // 隧道备用线路参数：与 TunnelService 第三条转发一致（系统属性/环境变量可覆盖）
    private static final int TUNNEL_PORT = intProp("odyssey.tunnel.ai-listen-port", 18083);
    private static final int TUNNEL_SLOT_MIN = intProp("odyssey.tunnel.slot-min", 2);
    private static final int TUNNEL_SLOT_MAX = intProp("odyssey.tunnel.slot-max", 21);
    private static final String TUNNEL_HOST = strProp("odyssey.tunnel.ai-target-host", "aikeyhub.gateway.mdzy");

    /** 上次成功的线路 base（置顶复用，避免每次先撞一遍不可用线路） */
    private volatile String lastGoodBase;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UserConfigStore configStore;

    /** 全局共享的上次调用时刻（毫秒），实现跨调用的间隔限速 */
    private long lastCallAt = 0;

    public AiGatewayClient(UserConfigStore configStore) {
        this.configStore = configStore;
    }

    /** AI 配置不完整或密钥无效（可识别异常，供任务层给出明确失败提示） */
    public static class AiConfigException extends RuntimeException {
        public AiConfigException(String message) {
            super(message);
        }
    }

    /** AI 调用失败（重试耗尽/网关拒绝等非配置类错误） */
    public static class AiCallException extends RuntimeException {
        public AiCallException(String message) {
            super(message);
        }

        public AiCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 网关返回 HTTP 错误状态（区别于连接层失败，用于决定同线路重试还是换线路） */
    private static class HttpErrorException extends IOException {
        final int status;

        HttpErrorException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** 一条候选线路：base 为连接地址；hostHeader 非空时需覆盖 Host 头（隧道线路，网关按 Host 路由） */
    private static class Route {
        final String base;
        final String hostHeader;

        Route(String base, String hostHeader) {
            this.base = base;
            this.hostHeader = hostHeader;
        }
    }

    /**
     * 候选线路：配置地址直连优先；仅当配置的域名就是 AI 网关域名时追加中继与隧道线路
     * （中继由在线代理的电脑代发，代理按目标 URL 自动带对 Host 头，无需改写；
     * 隧道为 127.0.0.{槽位}:18083，需写回 Host），上次成功的线路置顶复用。
     */
    private List<Route> candidateRoutes(String baseUrl) {
        List<Route> routes = new ArrayList<>();
        String host = hostOf(baseUrl);
        String path = pathOf(baseUrl);
        routes.add(new Route(trimEnd(baseUrl, '/'), null));
        if (TUNNEL_HOST.equals(host)) {
            routes.add(new Route("relay://" + TUNNEL_HOST + path, null));
            for (int i = TUNNEL_SLOT_MIN; i <= TUNNEL_SLOT_MAX; i++) {
                routes.add(new Route("http://127.0.0." + i + ":" + TUNNEL_PORT + path, TUNNEL_HOST));
            }
        }
        if (lastGoodBase != null) {
            for (int i = 0; i < routes.size(); i++) {
                if (routes.get(i).base.equals(lastGoodBase)) {
                    routes.add(0, routes.remove(i));
                    break;
                }
            }
        }
        return routes;
    }

    private static int intProp(String key, int def) {
        String v = System.getProperty(key, System.getenv().get(envKey(key)));
        try {
            return v == null || v.trim().isEmpty() ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String strProp(String key, String def) {
        String v = System.getProperty(key, System.getenv().get(envKey(key)));
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    /** 属性键转环境变量名：odyssey.tunnel.ai-listen-port → ODYSSEY_TUNNEL_AI_LISTEN_PORT */
    private static String envKey(String key) {
        return key.toUpperCase().replace('.', '_').replace('-', '_');
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (java.net.MalformedURLException e) {
            return "";
        }
    }

    private static String pathOf(String url) {
        try {
            return new java.net.URL(url).getPath();
        } catch (java.net.MalformedURLException e) {
            return "";
        }
    }

    /**
     * 正式对话调用：系统提示词 + 用户正文 → 模型正文文本。
     * 长输出被 max_tokens 截断（stop_reason=max_tokens）时自动带上下文续写并拼接。
     *
     * @param user 触发用户（AI 配置按其个人配置解析）
     * @param maxTokens 单轮 max_tokens（网关会先消耗在 thinking 上，报告类建议 ≥4096）
     */
    public String chat(String user, String system, String userText, int maxTokens) {
        UserConfig cfg = requireConfig(user);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", userText));

        StringBuilder all = new StringBuilder();
        long inTokens = 0;
        long outTokens = 0;
        for (int round = 0; ; round++) {
            JsonNode resp = postMessages(cfg, system, messages, maxTokens);
            String part = joinText(resp);
            all.append(part);
            inTokens += resp.path("usage").path("input_tokens").asLong(0);
            outTokens += resp.path("usage").path("output_tokens").asLong(0);
            String stop = resp.path("stop_reason").asText("");
            if (!"max_tokens".equals(stop) || all.length() == 0 || round >= MAX_CONTINUATIONS) {
                log.info("AI 调用完成（user={} {} {}）：轮数 {}，token in={} out={}",
                        user, cfg.getAiModel(), cfg.getAiBaseUrl(), round + 1, inTokens, outTokens);
                return all.toString();
            }
            // 续写：把已生成的部分作为 assistant 消息带回，要求从中断处继续
            messages.add(message("assistant", all.toString()));
            messages.add(message("user", "继续输出剩余内容，从中断处直接续写，不要重复已有内容。"));
            log.info("AI 输出被 max_tokens 截断（已 {} 字符），第 {} 次续写", all.length(), round + 1);
        }
    }

    /** 默认 max_tokens 的便捷重载 */
    public String chat(String user, String system, String userText) {
        return chat(user, system, userText, 4096);
    }

    /** 读取并校验指定用户的 AI 配置（每次调用现读，支持热更新）；缺失抛 AiConfigException */
    private UserConfig requireConfig(String user) {
        UserConfig cfg = configStore.get(user == null ? "" : user);
        if (isBlank(cfg.getAiBaseUrl()) || isBlank(cfg.getAiApiKey()) || isBlank(cfg.getAiModel())) {
            throw new AiConfigException(
                    "AI 配置不完整：请在「系统管理 → 全局配置」中填写 AI Base URL、API Key 与模型名");
        }
        return cfg;
    }

    /**
     * 单轮 HTTP 调用（含限速、多线路与重试），返回响应 JSON。
     * 线路按序尝试：连接层失败与 4xx（如直连 403 IP 白名单）立即换下一条线路；
     * 5xx/429 同线路重试；密钥无效（401）与线路无关，直接抛出。
     */
    private JsonNode postMessages(UserConfig cfg, String system,
                                  List<Map<String, Object>> messages, int maxTokens) {
        int timeout = of(cfg.getAiTimeoutMs(), DEF_TIMEOUT_MS);
        int maxRetries = of(cfg.getAiMaxRetries(), DEF_MAX_RETRIES);
        List<Route> routes = candidateRoutes(cfg.getAiBaseUrl());
        Exception last = null;
        for (Route route : routes) {
            String url = trimEnd(route.base, '/') + "/v1/messages";
            for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
                throttle(cfg);
                try {
                    JsonNode resp = doOnce(cfg, url, route.hostHeader, system, messages, maxTokens, timeout);
                    lastGoodBase = route.base;
                    return resp;
                } catch (AiConfigException e) {
                    throw e; // 密钥无效/配置类错误，与线路无关
                } catch (HttpErrorException e) {
                    last = e;
                    if ((e.status >= 500 || e.status == 429) && attempt <= maxRetries) {
                        log.warn("AI 调用失败（线路 {}，第 {}/{} 次）：{}，{}ms 后重试",
                                route.base, attempt, maxRetries + 1, e.getMessage(), RETRY_BACKOFF_MS);
                        sleep(RETRY_BACKOFF_MS);
                        continue;
                    }
                    break; // 其余 HTTP 错误换下一条线路
                } catch (IOException e) {
                    last = e;
                    log.info("AI 线路 {} 不可用，尝试下一条：{}", route.base, e.getMessage());
                    break;
                }
            }
        }
        throw new AiCallException("AI 调用失败："
                + (last == null ? "无可用线路" : last.getMessage()), last);
    }

    /** 真正发一次请求；非 2xx 按状态分类抛异常 */
    private JsonNode doOnce(UserConfig cfg, String url, String hostHeader, String system,
                            List<Map<String, Object>> messages, int maxTokens, int timeout)
            throws IOException {
        HttpURLConnection conn = RelayConnection.open(url);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(timeout);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", cfg.getAiApiKey());
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        if (hostHeader != null) {
            // 隧道线路：连接地址是回环槽位，但网关按 Host 头路由，必须写回真实域名
            // （依赖 JVM 参数 -Dsun.net.http.allowRestrictedHeaders=true，见 OdysseyApplication）
            conn.setRequestProperty("Host", hostHeader);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getAiModel());
        if (system != null && !system.isEmpty()) {
            body.put("system", system);
        }
        body.put("max_tokens", maxTokens);
        body.put("messages", messages);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(mapper.writeValueAsBytes(body));
        }

        int status = conn.getResponseCode();
        String text = readStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (status >= 400) {
            JsonNode node = parseOrEmpty(text);
            // 网关错误体兼容两种格式：{"error":{"message":..}} 与 {"message":..}
            String detail = node.path("error").path("message").asText(
                    node.path("message").asText(null));
            if (status == 401) {
                throw new AiConfigException("AI 密钥无效或无权限（HTTP 401）"
                        + (detail == null ? "" : "：" + detail));
            }
            // 403（如 IP 白名单）等 HTTP 错误视为线路/网关问题，参与换线
            throw new HttpErrorException(status,
                    "网关返回 HTTP " + status + (detail == null ? "" : "：" + detail));
        }
        return parseOrEmpty(text);
    }

    /** 调用间隔限速：距上次调用发起不足间隔则等待 */
    private void throttle(UserConfig cfg) {
        int interval = of(cfg.getAiCallIntervalMs(), DEF_INTERVAL_MS);
        if (interval <= 0) {
            return;
        }
        long wait;
        synchronized (this) {
            long now = System.currentTimeMillis();
            wait = lastCallAt + interval - now;
            lastCallAt = now + Math.max(0, wait);
        }
        if (wait > 0) {
            sleep(wait);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int of(Integer v, int def) {
        return v == null || v <= 0 ? def : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static JsonNode parseOrEmpty(String text) throws IOException {
        return new ObjectMapper().readTree(text == null || text.isEmpty() ? "{}" : text);
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * 连通性测试：向 {baseUrl}/v1/messages 发一次最小请求（max_tokens=64），
     * 根据网关返回判断配置是否可用。不抛异常，失败原因放入结果。
     * 多线路按序尝试（直连 → 隧道），任一线路成功即为可用。
     *
     * @return ok=是否可用；message=人类可读说明（成功含模型回复与耗时）
     */
    public Map<String, Object> testConnectivity(String baseUrl, String apiKey, String model) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        long start = System.currentTimeMillis();
        String lastMessage = "无可用线路";
        for (Route route : candidateRoutes(baseUrl)) {
            try {
                HttpURLConnection conn = RelayConnection.open(
                        trimEnd(route.base, '/') + "/v1/messages");
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(20000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                if (route.hostHeader != null) {
                    // 隧道线路按 Host 头路由，写回真实域名（同 doOnce，依赖 allowRestrictedHeaders）
                    conn.setRequestProperty("Host", route.hostHeader);
                }

                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "user");
                message.put("content", "连通测试，请只回复：pong");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                // 模型会先输出 thinking 块再输出 text 块，给足 token 保证有正文可回
                body.put("max_tokens", 64);
                body.put("messages", Collections.singletonList(message));

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(mapper.writeValueAsBytes(body));
                }

                int status = conn.getResponseCode();
                String text = readStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
                JsonNode node = mapper.readTree(text == null || text.isEmpty() ? "{}" : text);

                if (status >= 200 && status < 300) {
                    long latency = System.currentTimeMillis() - start;
                    String reply = firstText(node);
                    result.put("ok", true);
                    result.put("message", "连通正常（" + latency + " ms）"
                            + (reply.isEmpty() ? "" : "，模型回复：" + reply));
                    result.put("latency_ms", latency);
                    break;
                }
                String detail = node.path("error").path("message").asText(
                        node.path("message").asText(null));
                lastMessage = "网关返回 HTTP " + status + (detail == null ? "" : "：" + detail);
            } catch (Exception e) {
                lastMessage = "连接失败：" + e.getMessage();
            }
        }
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            result.put("message", lastMessage);
        }
        log.info("AI 连通性测试（{} {}）：{}", baseUrl, model, result.get("message"));
        return result;
    }

    /** 读取响应流为字符串；流为 null（无内容）时返回空串 */
    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        in.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 取 Anthropic 响应里的正文文本（第一个 type=text 的 content 块；thinking 块跳过），无则空串 */
    private static String firstText(JsonNode node) {
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    return block.path("text").asText("");
                }
            }
        }
        return "";
    }

    /** 拼接响应里全部 type=text 的正文块（续写轮次中正文可能拆成多块），跳过 thinking 块 */
    private static String joinText(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText(""));
                }
            }
        }
        return sb.toString();
    }

    private static String trimEnd(String s, char c) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == c) {
            end--;
        }
        return s.substring(0, end);
    }
}
