package org.odyssey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.odyssey.model.OnesComment;
import org.odyssey.model.OnesHistory;
import org.odyssey.model.OnesRef;
import org.odyssey.model.OnesTaskMessages;
import org.odyssey.model.OnesTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ONES 平台只读数据访问客户端（工单助手用）。
 *
 * 通道与验证依据见 WorkOrderNeed/工单数据结构.md、工单数据获取-使用调研.md：
 * - GraphQL 直查（t=group-task-data）：批量工单主记录，filterGroup 只支持 in 类操作符，
 *   时间/更新戳过滤不可用，因此用「排序 + 分页 + 早停」实现范围拉取与增量；
 * - messages 接口：评论（type=discussion）与字段变更历史（type=system）；
 * - 根级 users 查询：UUID → 姓名映射。
 *
 * Cookie 为个人配置（ONES 个人身份），由上层（同步编排）解析后显式传入各方法；
 * 失效时 ONES 返回 401/AuthFailure.InvalidToken，
 * 本客户端统一抛 {@link OnesAuthException} 供上层做页面提示。
 */
@Service
public class OnesClient {

    private static final Logger log = LoggerFactory.getLogger(OnesClient.class);

    /** ONES 站点与团队/项目标识（QDT 工单项目，当前固定；如迁移环境需同步修改） */
    public static final String ONES_BASE_URL = "http://10.10.191.213/project";
    public static final String TEAM_UUID = "3HHnQbuG";
    public static final String PROJECT_UUID = "NfvccPP54SaDCYAS";
    /** QDT 项目内「工单」类型（实测该团队另一个工单 UUID 及 LIMS/MES 等子类型均为 0 条） */
    public static final String ISSUE_TYPE_UUID = "BiGG965x";

    /** 工单查询 SELECT 子句：全部经实测验证（2026-11-02） */
    private static final String TASK_SELECT =
            "uuid number name descriptionText status { name category } priority { value } "
                    + "assign { uuid name } owner { uuid name } "
                    + "_AST3JVZw { uuid value } products { uuid name } productModules { uuid name } "
                    + "watchers { uuid name } "
                    + "createTime serverUpdateStamp deadline(unit: ONESDATE)";

    private static final int PAGE_SIZE = 500;
    /** 分页安全上限：约 5 万条，远超实际工单量（~8600），防接口异常导致死循环 */
    private static final int MAX_PAGES = 100;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 多线路：服务器出网受限，直连地址不可达时依次尝试：
     * 1) 用户电脑的中继代理（RelayService，HTTP 轮询代发，主用）；
     * 2) 用户电脑的 SSH 反向隧道（TunnelService 第二条转发 127.0.0.{槽位}:18082 → ONES，兜底）。
     * 连接层失败自动换下一条线路；Cookie 类鉴权错误与线路无关，立即抛出。
     */
    private volatile String lastGoodBase;

    /** 中继线路：协议头后的部分为代理代发的目标主机（端口缺省 80） */
    static final String RELAY_BASE_URL = relayBaseOf(ONES_BASE_URL);

    /** 由 http 线路地址推导中继线路地址：http://10.10.191.213/project → relay://10.10.191.213/project（路径必须保留） */
    static String relayBaseOf(String httpBase) {
        try {
            java.net.URI u = java.net.URI.create(httpBase);
            return "relay://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getPath();
        } catch (Exception e) {
            return "relay://invalid";
        }
    }

    /** 隧道线路参数：与 TunnelService 的转发配置一致（系统属性/环境变量可覆盖） */
    private static final int TUNNEL_PORT = intProp("odyssey.tunnel.ones-listen-port", 18082);
    private static final int TUNNEL_SLOT_MIN = intProp("odyssey.tunnel.slot-min", 2);
    private static final int TUNNEL_SLOT_MAX = intProp("odyssey.tunnel.slot-max", 21);

    /** 全线路失败后的重扫：轮数与轮间隔（覆盖 Wi-Fi 漫游导致的隧道断连窗口，实测最长约 60 秒） */
    private static final int SWEEP_MAX = 7;
    private static final long SWEEP_PAUSE_MS = 15_000;

    public OnesClient() {
    }

    private static int intProp(String key, int def) {
        String env = System.getenv(key.toUpperCase().replace('.', '_').replace('-', '_'));
        String v = System.getProperty(key, env);
        try {
            return v == null || v.trim().isEmpty() ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 全部候选线路：直连地址 → 中继代理 → 隧道回环线路；上次连通的线路置顶复用 */
    private List<String> candidateBaseUrls() {
        List<String> candidates = new ArrayList<>();
        candidates.add(ONES_BASE_URL);
        candidates.add(RELAY_BASE_URL);
        for (int i = TUNNEL_SLOT_MIN; i <= TUNNEL_SLOT_MAX; i++) {
            candidates.add("http://127.0.0." + i + ":" + TUNNEL_PORT + "/project");
        }
        if (lastGoodBase != null) {
            candidates.remove(lastGoodBase);
            candidates.add(0, lastGoodBase);
        }
        return candidates;
    }

    /**
     * 多线路调用：连接层失败（IOException）换下一条线路；鉴权失败与线路无关，立即抛出。
     *
     * 全部线路扫完仍失败时，暂停 {@value #SWEEP_PAUSE_MS}ms 再扫一轮（最多 {@value #SWEEP_MAX} 轮）：
     * 隧道客户端电脑是 Wi-Fi，网卡漫游会周期性掐断 SSH 长连接，启动脚本约 15 秒后自动重连，
     * 这段断连窗口内的同步请求靠跨轮重试自愈，不必让整个同步失败。
     */
    private <T> T forAnyBase(HttpCall<T> call) throws IOException {
        IOException primary = null;   // 直连主线路的错误（根因，优先报告）
        IOException last = null;      // 最后一次失败（备用线路）
        for (int sweep = 1; sweep <= SWEEP_MAX; sweep++) {
            if (sweep > 1) {
                log.info("ONES 全部线路暂不可用，{}ms 后重扫第 {} 轮（隧道自动重连中）", SWEEP_PAUSE_MS, sweep);
                try {
                    Thread.sleep(SWEEP_PAUSE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            for (String base : candidateBaseUrls()) {
                try {
                    T result = call.run(base);
                    lastGoodBase = base;
                    return result;
                } catch (OnesAuthException e) {
                    throw e;
                } catch (IOException e) {
                    if (primary == null) {
                        primary = e;
                    }
                    if (last != e) {
                        log.info("ONES 线路 {} 不可用，尝试下一条：{}", base, e.getMessage());
                    }
                    last = e;
                }
            }
        }
        if (primary == null) {
            throw new IOException("无可用的 ONES 线路");
        }
        // 全部轮次失败：报告主线路错误并附线路提示（否则只显示最后一个候选的
        // "Connection refused"，掩盖「直连不通 + 中继/隧道均未在线」的真实情况）
        throw new IOException("ONES 不可达（" + primary.getMessage()
                + "），中继代理与隧道线路也均未在线。请在可访问 ONES 的电脑上重新运行隧道初始化脚本"
                + "（安装中继代理）", primary);
    }

    private interface HttpCall<T> {
        T run(String base) throws IOException;
    }

    /** ONES 鉴权失败（Cookie 失效/未配置） */
    public static class OnesAuthException extends RuntimeException {
        public OnesAuthException(String message) {
            super(message);
        }
    }

    /**
     * 拉取创建时间不早于 sinceMs 的全部工单。用于首次全量同步。
     *
     * 实测（2026-11-03）：ONES tasks API 不执行 pagination 参数，单次请求即返回全量；
     * 因此 orderBy + 早停只是请求层的兜底，真正的范围过滤靠客户端按时间戳筛选。
     */
    public List<OnesTicket> fetchTicketsCreatedSince(String cookie, long sinceMs) throws IOException {
        List<OnesTicket> all = fetchPaged(cookie, "{createTime: DESC}",
                page -> page.isEmpty() || page.get(page.size() - 1).getCreateTimeMs() < sinceMs);
        List<OnesTicket> matched = new ArrayList<>();
        for (OnesTicket t : all) {
            if (t.getCreateTimeMs() >= sinceMs) {
                matched.add(t);
            }
        }
        log.info("全量拉取：{} 条中按创建时间下界过滤得 {} 条", all.size(), matched.size());
        return matched;
    }

    /**
     * 拉取更新时间戳晚于 sinceMs 的全部工单。用于每日增量同步
     * （serverUpdateStamp 过滤操作符不可用，且 pagination 参数不生效，
     * 实际做法：全量拉回单响应后按客户端水位筛选，见 fetchTicketsCreatedSince 注释）。
     */
    public List<OnesTicket> fetchTicketsUpdatedSince(String cookie, long sinceMs) throws IOException {
        List<OnesTicket> all = fetchPaged(cookie, "{serverUpdateStamp: DESC}",
                page -> page.isEmpty()
                        || page.get(page.size() - 1).getServerUpdateStampMs() < sinceMs);
        List<OnesTicket> changed = new ArrayList<>();
        for (OnesTicket t : all) {
            if (t.getServerUpdateStampMs() >= sinceMs) {
                changed.add(t);
            }
        }
        log.info("增量拉取：{} 条中按水位过滤得 {} 条变更工单", all.size(), changed.size());
        return changed;
    }

    /** 分页遍历工单：orderBy 为 GraphQL 排序子句，stop 判断当前页读完后是否停止 */
    private List<OnesTicket> fetchPaged(String cookie, String orderBy,
            java.util.function.Predicate<List<OnesTicket>> stop)
            throws IOException {
        List<OnesTicket> result = new ArrayList<>();
        for (int offset = 0; offset < MAX_PAGES * PAGE_SIZE; offset += PAGE_SIZE) {
            String query = "query { buckets(groupBy: {tasks: {}}) { tasks("
                    + "filterGroup: [{project_in: [\"" + PROJECT_UUID + "\"], issueType_in: [\"" + ISSUE_TYPE_UUID + "\"]}], "
                    + "orderBy: " + orderBy + ", "
                    + "pagination: {limit: " + PAGE_SIZE + ", offset: " + offset + "}) { "
                    + TASK_SELECT + " } } }";
            List<OnesTicket> page = parseTickets(postGraphQL(cookie, query));
            result.addAll(page);
            if (stop.test(page)) {
                break;
            }
        }
        log.info("ONES 工单拉取完成（{}）：共 {} 条", orderBy, result.size());
        return result;
    }

    /** 拉取单条工单的动态流（评论 + 字段变更历史） */
    public OnesTaskMessages fetchMessages(String cookie, String taskUuid) throws IOException {
        JsonNode node = forAnyBase(base ->
                getJson(cookie, base + "/api/project/team/" + TEAM_UUID + "/task/" + taskUuid + "/messages"));
        return parseMessages(taskUuid, node);
    }

    /** 拉取团队全部用户（uuid → 姓名），供评论/流转历史的操作人解析 */
    public Map<String, String> fetchUserNames(String cookie) throws IOException {
        JsonNode data = postGraphQL(cookie, "query { users { uuid name } }");
        JsonNode root = data.has("users") ? data : data.path("data"); // 兼容响应根或 data 节点
        Map<String, String> map = new HashMap<>();
        for (JsonNode u : root.path("users")) {
            map.put(u.path("uuid").asText(), u.path("name").asText());
        }
        return map;
    }

    // ---- 解析（包级可见，供单元测试喂样例 JSON） ----

    static List<OnesTicket> parseTickets(JsonNode data) {
        // 兼容两种入参：GraphQL 响应根（{data:{buckets:...}}）或直接的 buckets 节点
        JsonNode root = data.has("buckets") ? data : data.path("data");
        List<OnesTicket> list = new ArrayList<>();
        for (JsonNode bucket : root.path("buckets")) {
            for (JsonNode t : bucket.path("tasks")) {
                OnesTicket ticket = new OnesTicket();
                ticket.setUuid(t.path("uuid").asText());
                ticket.setNumber(t.path("number").asInt());
                ticket.setName(t.path("name").asText(null));
                ticket.setDescriptionText(t.path("descriptionText").asText(null));
                ticket.setStatusCategory(t.path("status").path("category").asText(null));
                ticket.setStatusName(t.path("status").path("name").asText(null));
                ticket.setPriority(t.path("priority").path("value").asText(null));
                ticket.setAssignUuid(t.path("assign").path("uuid").asText(null));
                ticket.setAssignName(t.path("assign").path("name").asText(null));
                ticket.setOwnerUuid(t.path("owner").path("uuid").asText(null));
                ticket.setOwnerName(t.path("owner").path("name").asText(null));
                // 「客户（QDT）」自定义字段：value 即客户中文名
                ticket.setCustomerUuid(t.path("_AST3JVZw").path("uuid").asText(null));
                ticket.setCustomerName(t.path("_AST3JVZw").path("value").asText(null));
                ticket.setCreateTimeMs(microsToMillis(t.path("createTime").asLong(0)));
                ticket.setServerUpdateStampMs(microsToMillis(t.path("serverUpdateStamp").asLong(0)));
                String deadline = t.path("deadline").asText(null);
                ticket.setDeadline(deadline == null || deadline.isEmpty() ? null : deadline);
                for (JsonNode p : t.path("products")) {
                    ticket.getProducts().add(new OnesRef(p.path("uuid").asText(), p.path("name").asText()));
                }
                for (JsonNode m : t.path("productModules")) {
                    ticket.getModules().add(new OnesRef(m.path("uuid").asText(), m.path("name").asText()));
                }
                for (JsonNode w : t.path("watchers")) {
                    ticket.getWatchers().add(new OnesRef(w.path("uuid").asText(), w.path("name").asText()));
                }
                list.add(ticket);
            }
        }
        return list;
    }

    static OnesTaskMessages parseMessages(String taskUuid, JsonNode node) {
        OnesTaskMessages result = new OnesTaskMessages();
        JsonNode items = node.isArray() ? node : node.path("messages"); // 兼容数组或包装两种返回
        if (!items.isArray()) {
            return result;
        }
        for (JsonNode item : items) {
            String type = item.path("type").asText();
            long timeMs = microsToMillis(item.path("send_time").asLong(0));
            if ("discussion".equals(type)) {
                result.getComments().add(new OnesComment(
                        item.path("uuid").asText(), taskUuid,
                        item.path("from").asText(null), timeMs,
                        item.path("text").asText(null)));
            } else if ("system".equals(type) && "update".equals(item.path("action").asText())
                    && item.path("ext").path("field_name").asText() != null
                    && !item.path("ext").path("field_name").asText().isEmpty()) {
                JsonNode ext = item.path("ext");
                result.getHistories().add(new OnesHistory(
                        taskUuid, timeMs, item.path("subject_id").asText(null),
                        ext.path("field_name").asText(),
                        optionValue(ext.path("old_option"), ext.path("old_value")),
                        optionValue(ext.path("new_option"), ext.path("new_value"))));
            }
        }
        return result;
    }

    /** 变更值取值：选项型字段取 old_option/new_option.name，其余回落 old_value/new_value */
    private static String optionValue(JsonNode option, JsonNode plain) {
        if (option.isObject()) {
            return option.path("name").asText(null);
        }
        return plain.isMissingNode() || plain.isNull() ? null : plain.asText();
    }

    static long microsToMillis(long micros) {
        return micros / 1000;
    }

    // ---- HTTP ----

    private JsonNode postGraphQL(String cookie, String query) throws IOException {
        Map<String, String> body = new HashMap<>();
        body.put("query", query);
        byte[] payload = mapper.writeValueAsBytes(body);

        return forAnyBase(base -> {
            HttpURLConnection conn = openConnection(base + "/api/project/team/"
                    + TEAM_UUID + "/items/graphql?t=group-task-data", cookie);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            return readResponse(conn);
        });
    }

    private JsonNode getJson(String cookie, String url) throws IOException {
        HttpURLConnection conn = openConnection(url, cookie);
        conn.setRequestMethod("GET");
        return readResponse(conn);
    }

    private HttpURLConnection openConnection(String url, String cookie) throws IOException {
        // RelayConnection.open：http 开头直连，relay:// 开头经中继代理，其余逻辑一致
        HttpURLConnection conn = RelayConnection.open(url);
        // 连接超时取短值：不可达线路快速失败换下一条（正常连接 <1ms，隧道为回环）
        conn.setConnectTimeout(3_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Cookie", requireCookie(cookie));
        return conn;
    }

    private String requireCookie(String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) {
            throw new OnesAuthException("未配置 ONES Cookie，请到「个人中心 → 工单助手配置」填写");
        }
        return cookie.trim();
    }

    private JsonNode readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        String text = readStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (status == 401 || text.contains("AuthFailure.InvalidToken")) {
            throw new OnesAuthException("ONES Cookie 已失效，请到「个人中心 → 工单助手配置」更新");
        }
        if (status >= 400) {
            throw new IOException("ONES 请求失败：HTTP " + status + "，" + abbreviate(text));
        }
        return mapper.readTree(text == null || text.isEmpty() ? "{}" : text);
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        in.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
