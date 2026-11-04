package org.odyssey.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Odyssey 中继代理（服务器出网受限，由本机代发对内网上游的 HTTP 请求）。
 *
 * 零第三方依赖（Java 8 标准库），由应用初始化脚本安装并常驻：
 *   java -cp odyssey-relay-agent.jar org.odyssey.agent.RelayAgentMain
 *        --server http://10.0.106.110:8787 --token <令牌> --allow 10.10.191.213,...
 *
 * 工作循环：POST /api/relay/poll（行式 TSV，协议见 RelayController）上报已完成
 * 任务的结果并领走新任务，本地线程池并发代发。目标主机必须在 --allow 白名单内，
 * 防止令牌泄露后被用来探测内网其他地址。
 */
public class RelayAgentMain {

    /** 单响应体大小上限（全量工单 GraphQL 响应实测约 0.7MB，留足余量） */
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    /** 单个任务代发超时（与服务器侧调用方的读超时量级一致） */
    private static final int TARGET_CONNECT_TIMEOUT = 5_000;
    private static final int TARGET_READ_TIMEOUT = 120_000;
    /** 代理本地并发线程数 */
    private static final int WORKERS = 6;

    private final String serverUrl;
    private final String token;
    private final Set<String> allowedHosts;
    private final long idleIntervalMs;

    /** 待上报的执行结果（双端队列：上报失败时从队首放回重传） */
    private final java.util.concurrent.ConcurrentLinkedDeque<String> resultLines =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);

    public static void main(String[] args) {
        // 禁用 HttpURLConnection 的 keep-alive 连接池：轮询间隔里连接可能已被服务器关闭
        // （代发大响应期间必然超过 keep-alive 超时），复用死连接报 Error writing to server。
        // 注意 setRequestProperty("Connection","Close") 是 JDK 受限头，会被静默忽略，必须用系统属性。
        System.setProperty("http.keepAlive", "false");
        Map<String, String> opts = parseArgs(args);
        String server = opts.get("server");
        String token = opts.get("token");
        String allow = opts.get("allow");
        if (server == null || token == null || allow == null) {
            System.out.println("用法: --server <Odyssey地址> --token <令牌> --allow <host1,host2> [--interval 毫秒]");
            return;
        }
        long idle = opts.containsKey("interval") ? Long.parseLong(opts.get("interval")) : 2000L;
        try {
            new RelayAgentMain(server, token, allow, idle).run();
        } catch (Exception e) {
            System.out.println("[relay] 致命错误: " + e);
            System.exit(1);
        }
    }

    RelayAgentMain(String serverUrl, String token, String allow, long idleIntervalMs) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.token = token;
        this.idleIntervalMs = idleIntervalMs;
        this.allowedHosts = new HashSet<>();
        for (String host : allow.split(",")) {
            String h = host.trim().toLowerCase();
            if (!h.isEmpty()) {
                this.allowedHosts.add(h);
            }
        }
    }

    private void run() {
        System.out.println("[relay] 启动：server=" + serverUrl + " 白名单=" + allowedHosts);
        while (true) {
            long cycleStart = System.currentTimeMillis();
            try {
                boolean busy = pollOnce();
                // 有任务或待上报结果时快速轮询，空闲时按 idle 间隔报到
                long sleep = busy ? 200L : idleIntervalMs;
                sleepUntil(cycleStart + sleep);
            } catch (Exception e) {
                // 服务器暂不可达/网络切换：退避后重试，不打断常驻
                System.out.println("[relay] 轮询异常（退避重试）: " + e);
                e.printStackTrace(System.out);
                sleepUntil(cycleStart + 5000L);
            }
        }
    }

    /** 一轮报到：上报结果 + 领任务并提交执行。返回本轮是否拿到任务或有待上报结果 */
    private boolean pollOnce() throws IOException {
        StringBuilder body = new StringBuilder("AGENT\t").append(token).append('\n');
        List<String> batch = new ArrayList<>();
        String line;
        while ((line = resultLines.pollFirst()) != null) {
            batch.add(line);
            body.append(line).append('\n');
        }
        boolean hadResults = !batch.isEmpty();

        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            if (hadResults) {
                System.out.println("[relay] 上报 " + batch.size() + " 行结果，body "
                        + payload.length + "B");
            }
            byte[] resp = httpPost(serverUrl + "/api/relay/poll", payload, "text/plain", 10_000);
            List<String[]> jobs = new ArrayList<>();
            for (String l : new String(resp, StandardCharsets.UTF_8).split("\r?\n")) {
                if (l.startsWith("JOB\t")) {
                    String[] f = l.split("\t", -1);
                    if (f.length >= 6) {
                        jobs.add(f);
                    }
                }
            }
            for (String[] job : jobs) {
                submit(job[1], job[2], job[3], job[4], job[5]);
            }
            return !jobs.isEmpty() || hadResults;
        } catch (IOException e) {
            // 上报失败：结果放回队首下轮重传（丢了会导致服务器任务永远等不到结果）
            for (int i = batch.size() - 1; i >= 0; i--) {
                resultLines.addFirst(batch.get(i));
            }
            throw e;
        }
    }

    /** 提交一个代发任务到线程池，完成后把结果行放入上报队列 */
    private void submit(final String id, final String method, final String url,
            final String headersB64, final String bodyB64) {
        workers.submit(() -> resultLines.add(execute(id, method, url, headersB64, bodyB64)));
    }

    /** 代发单个请求并生成 RESULT 行；任何失败都以上游 502 的形式回传（服务器侧按 HTTP 错误处理） */
    String execute(String id, String method, String url, String headersB64, String bodyB64) {
        try {
            URL target = new URL(url);
            String host = target.getHost().toLowerCase();
            if (!allowedHosts.contains(host)) {
                System.out.println("[relay] 拒绝非白名单目标: " + host);
                return resultLine(id, 502, "", ("非白名单目标: " + host).getBytes(StandardCharsets.UTF_8));
            }
            HttpURLConnection conn = (HttpURLConnection) target.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TARGET_CONNECT_TIMEOUT);
            conn.setReadTimeout(TARGET_READ_TIMEOUT);
            for (String h : new String(Base64.getDecoder().decode(headersB64), StandardCharsets.UTF_8).split("\n")) {
                int idx = h.indexOf(':');
                if (idx > 0) {
                    conn.setRequestProperty(h.substring(0, idx).trim(), h.substring(idx + 1).trim());
                }
            }
            byte[] reqBody = Base64.getDecoder().decode(bodyB64);
            if (reqBody.length > 0) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(reqBody);
                }
            }
            int status = conn.getResponseCode();
            byte[] respBody = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            String respHeaders = collectHeaders(conn);
            System.out.println("[relay] " + method + " " + host + " -> " + status
                    + " (" + respBody.length + "B)");
            return resultLine(id, status, respHeaders, respBody);
        } catch (Exception e) {
            System.out.println("[relay] " + method + " " + url + " 失败: " + e);
            return resultLine(id, 502, "", ("relay: " + e).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 结果行：RESULT\t<id>\t<status>\t<响应头b64>\t<响应体b64>\t<编码>
     * 响应体一律 gzip 压缩（全量工单 GraphQL 响应可达 9MB，经回传通道必须压缩，
     * 编码字段告知服务器解压；空串表示未压缩，兼容旧版）。
     */
    private static String resultLine(String id, int status, String headers, byte[] body) {
        byte[] payload = body;
        String encoding = "";
        try {
            payload = gzip(body);
            encoding = "gzip";
        } catch (IOException e) {
            // 压缩失败退回原文传输（内存字节流压缩不会失败，仅为完整性兜底）
        }
        return "RESULT\t" + id + "\t" + status + "\t"
                + Base64.getEncoder().encodeToString(headers.getBytes(StandardCharsets.UTF_8)) + "\t"
                + Base64.getEncoder().encodeToString(payload) + "\t" + encoding;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(buf)) {
            gz.write(data);
        }
        return buf.toByteArray();
    }

    /** 采集响应头（排除体相关的传输头，服务器侧按需读取） */
    private static String collectHeaders(HttpURLConnection conn) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() == null) {
                continue; // 状态行
            }
            if ("Transfer-Encoding".equalsIgnoreCase(e.getKey()) || "Content-Length".equalsIgnoreCase(e.getKey())) {
                continue;
            }
            for (String v : e.getValue()) {
                sb.append(e.getKey()).append(": ").append(v).append('\n');
            }
        }
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_RESPONSE_BYTES) {
                throw new IOException("响应超过上限 " + MAX_RESPONSE_BYTES + " 字节");
            }
        }
        in.close();
        return buf.toByteArray();
    }

    private static byte[] httpPost(String url, byte[] body, String contentType, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        // 禁用 keep-alive：轮询间隔里连接可能已被服务器关闭（代发大响应期间必然超时），
        // 复用死连接会报 Error writing to server 且失败重试也救不回（JDK 对 POST 不自动重试）
        conn.setRequestProperty("Connection", "Close");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        int status = conn.getResponseCode();
        byte[] resp = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (status != 200) {
            throw new IOException("HTTP " + status + ": " + new String(resp, StandardCharsets.UTF_8));
        }
        return resp;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            String key = args[i].startsWith("--") ? args[i].substring(2) : args[i];
            opts.put(key, args[i + 1]);
        }
        return opts;
    }

    private static void sleepUntil(long deadlineMs) {
        long remain = deadlineMs - System.currentTimeMillis();
        if (remain > 0) {
            try {
                Thread.sleep(remain);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
