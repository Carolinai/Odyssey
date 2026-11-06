package org.odyssey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import org.odyssey.config.OdysseyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 中继服务（替代 SSH 反向隧道的主要线路，原因见 TunnelService 类注释）：
 *
 * 服务器出网受限，但各用户电脑可以访问 ONES/Jenkins/AI 网关。用户电脑运行中继代理
 * （org.odyssey.agent.RelayAgentMain，经初始化脚本安装）后定期向服务器报到并「领任务」；
 * 服务器侧各客户端（OnesClient/JenkinsService/AiGatewayClient）把原本直连的上游请求
 * 交给本服务排入队列，任何在线代理认领后在本机代发并把响应交回。
 *
 * 多用户轮询：代理只凭令牌报到，服务器不关心是哪台电脑；任务被「当前在线」的任意代理
 * 认领，多个用户在线时自然分摊，全部不在线时任务等待（受调用方超时约束）。
 *
 * 代理接口协议见 RelayController（行式 TSV + base64，代理端零 JSON 依赖）。
 */
@Service
public class RelayService {

    private static final Logger log = LoggerFactory.getLogger(RelayService.class);

    /** 代理报到的判定窗口：超过该时长没有报到视为离线（代理轮询间隔为秒级） */
    private static final long AGENT_ONLINE_MS = 15_000;
    /** 任务被代理认领后允许的最长执行时长，超时收回队列重新分派 */
    private static final long CLAIM_LEASE_MS = 90_000;
    /** 同一任务最多收回重派次数，超过则判定失败（调用方切下一条线路） */
    private static final int MAX_RECLAIM = 2;
    /** 一次报到最多领走的任务数（代理本地并发执行） */
    private static final int MAX_JOBS_PER_POLL = 8;

    /** 一条待代发的上游请求 */
    public static class RelayJob {
        public final String id;
        public final String method;
        public final String url;
        /** 请求头序列化：一行一个「名称: 值」 */
        public final String headers;
        public final byte[] body;
        /** 结果回传后 complete；null=超时/失败 */
        final CompletableFuture<RelayResult> future = new CompletableFuture<>();
        volatile String claimedBy;
        volatile long claimedAtMs;
        volatile int reclaimCount;

        RelayJob(String method, String url, String headers, byte[] body) {
            this.id = UUID.randomUUID().toString().replace("-", "");
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    /** 上游响应（经代理交回） */
    public static class RelayResult {
        public final int status;
        public final String headers;
        public final byte[] body;

        public RelayResult(int status, String headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    private final OdysseyProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 待分派任务队列 */
    private final ConcurrentLinkedQueue<RelayJob> pending = new ConcurrentLinkedQueue<>();
    /** 全部未完成任务（含已认领），按 id 索引供结果回传匹配 */
    private final Map<String, RelayJob> jobsById = new ConcurrentHashMap<>();
    /** 已登记代理：用户名 → 令牌 */
    private Map<String, String> tokens = new LinkedHashMap<>();
    /** 代理在线状态：令牌 → 最近报到的用户名与时间 */
    private final Map<String, AgentPresence> presence = new ConcurrentHashMap<>();

    private static class AgentPresence {
        volatile String user;
        volatile long lastSeenMs;
    }

    public RelayService(OdysseyProperties props) {
        this.props = props;
        loadTokens();
        // 桥接给 RelayConnection（它可能在不经过 Spring 注入的调用点被构造，如登录前校验）
        RelayConnection.SpringHolder.relayService = this;
    }

    // ---------- 服务器侧调用入口 ----------

    /**
     * 经中继代发一次上游请求。当前无代理在线时立即抛出（调用方切换下一条线路，不空等）；
     * 有代理但执行超时同样抛出。请求头序列化为一行一个「名称: 值」。
     */
    public RelayResult execute(String method, String url, Map<String, String> headers,
            byte[] body, long timeoutMs) throws IOException {
        if (onlineAgents().isEmpty()) {
            throw new IOException("中继代理不在线");
        }
        StringBuilder hs = new StringBuilder();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                hs.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        RelayJob job = new RelayJob(method, url, hs.toString(), body == null ? new byte[0] : body);
        jobsById.put(job.id, job);
        pending.add(job);
        try {
            RelayResult result = job.future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result == null) {
                throw new IOException("中继执行失败");
            }
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(job);
            jobsById.remove(job.id);
            job.future.complete(null);
            throw new IOException("中继执行超时（" + (timeoutMs / 1000) + " 秒）");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("中继执行失败：" + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("中继等待被中断");
        }
    }

    /** 当前在线代理对应的用户名列表（状态展示与调用前探测用） */
    public List<String> onlineAgents() {
        long now = System.currentTimeMillis();
        List<String> users = new ArrayList<>();
        for (AgentPresence p : presence.values()) {
            if (now - p.lastSeenMs <= AGENT_ONLINE_MS) {
                users.add(p.user);
            }
        }
        return users;
    }

    // ---------- 代理侧接口（RelayController 调用） ----------

    /**
     * 代理报到并领任务：登记在线状态、回传上次领走的任务结果、按队列顺序派发新任务。
     *
     * @param token   代理令牌（初始化脚本下发，未知令牌直接拒绝）
     * @param results 代理交回的结果，元素为 {id, status, headers, body}
     * @return 领走的任务列表，元素为 {id, method, url, headers, body}（headers/body 为 base64）
     */
    public List<Map<String, Object>> poll(String token, List<Map<String, Object>> results) {
        AgentPresence me = registerPresence(token);
        if (me == null) {
            return null;
        }
        me.lastSeenMs = System.currentTimeMillis();

        // 1. 收结果：匹配任务并 complete；任务已不在册（超时清理过）则丢弃
        if (results != null) {
            for (Map<String, Object> r : results) {
                String id = str(r.get("id"));
                RelayJob job = id == null ? null : jobsById.get(id);
                if (job == null || !me.user.equals(job.claimedBy)) {
                    continue;
                }
                int status = r.get("status") instanceof Number ? ((Number) r.get("status")).intValue() : 502;
                byte[] body = r.get("body") instanceof String
                        ? Base64.getDecoder().decode((String) r.get("body")) : new byte[0];
                if ("gzip".equals(r.get("encoding"))) {
                    body = gunzip(body);
                }
                // 响应头与 body 同为 base64（代理 resultLine 编码），必须解码成明文行，
                // 否则调用方读到的响应头是乱码（Set-Cookie 等全部丢失）
                String headers = r.get("headers") instanceof String
                        ? new String(Base64.getDecoder().decode((String) r.get("headers")),
                                StandardCharsets.UTF_8)
                        : "";
                job.future.complete(new RelayResult(status, headers, body));
                jobsById.remove(id);
            }
        }

        // 2. 派发：从队列认领（认领即从队列移除， leases 见定时清扫）
        List<Map<String, Object>> out = new ArrayList<>();
        RelayJob job;
        while (out.size() < MAX_JOBS_PER_POLL && (job = pending.poll()) != null) {
            job.claimedBy = me.user;
            job.claimedAtMs = System.currentTimeMillis();
            out.add(jobView(job));
        }
        return out;
    }

    /** 认领超时的任务收回队列重派（代理掉线/执行中断的自愈） */
    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void reclaimStaleJobs() {
        long now = System.currentTimeMillis();
        for (RelayJob job : jobsById.values()) {
            if (job.claimedBy != null && now - job.claimedAtMs > CLAIM_LEASE_MS && !job.future.isDone()) {
                if (job.reclaimCount++ < MAX_RECLAIM) {
                    job.claimedBy = null;
                    pending.add(job);
                    log.info("中继任务超时收回重派（第 {} 次）：{}", job.reclaimCount, job.url);
                } else {
                    jobsById.remove(job.id);
                    job.future.complete(null);
                    log.warn("中继任务重派 {} 次仍失败：{}", MAX_RECLAIM, job.url);
                }
            }
        }
    }

    // ---------- 令牌管理（enroll 时签发） ----------

    /** 为用户签发（或复用）中继令牌；令牌文件在数据目录 relay/agents.json */
    public synchronized String issueToken(String username) {
        String existing = tokens.get(username);
        if (existing != null) {
            return existing;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(username, token);
        saveTokens();
        return token;
    }

    /** 报到令牌校验：合法返回该代理的登记用户名，否则 null */
    private AgentPresence registerPresence(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        synchronized (this) {
            for (Map.Entry<String, String> e : tokens.entrySet()) {
                if (e.getValue().equals(token)) {
                    AgentPresence p = presence.get(token);
                    if (p == null) {
                        p = new AgentPresence();
                        presence.put(token, p);
                    }
                    p.user = e.getKey();
                    return p;
                }
            }
        }
        return null;
    }

    private Path tokensFile() {
        return java.nio.file.Paths.get(props.getDataDir(), "relay", "agents.json");
    }

    private void loadTokens() {
        Path file = tokensFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            MapType type = mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class);
            tokens = mapper.readValue(Files.readAllBytes(file), type);
        } catch (IOException e) {
            log.error("中继令牌文件读取失败（忽略，按空处理）：{}", file, e);
            tokens = new LinkedHashMap<>();
        }
    }

    private synchronized void saveTokens() {
        try {
            Path file = tokensFile();
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "relay-agents-", ".tmp");
            Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tokens));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写入中继令牌文件失败", e);
        }
    }

    // ---------- 序列化 ----------

    /** 任务下发给代理的视图（headers/body base64，供 TSV 传输） */
    static Map<String, Object> jobView(RelayJob job) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", job.id);
        m.put("method", job.method);
        m.put("url", job.url);
        m.put("headers", Base64.getEncoder().encodeToString(
                job.headers.getBytes(StandardCharsets.UTF_8)));
        m.put("body", Base64.getEncoder().encodeToString(job.body));
        return m;
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    /** 代理回传体 gzip 解压（代理侧统一压缩以适应回传通道的大小限制） */
    private static byte[] gunzip(byte[] data) {
        try {
            java.io.InputStream in = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(data));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("中继结果解压失败，按原样返回", e);
            return data;
        }
    }
}
