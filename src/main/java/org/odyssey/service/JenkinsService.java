package org.odyssey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.odyssey.config.JenkinsProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jenkins 脚本代理：执行 Groovy 脚本并解析结果。
 * 多线路：首选配置的 base-url，其次用户电脑的中继代理，最后各 SSH 隧道候选线路
 * （127.0.0.N:18081），连上一次后缓存该线路优先复用。
 * 注意：提交给 Jenkins 的脚本必须为纯 ASCII——该版本 Jenkins 对表单中的
 * 非 ASCII 字符解析会直接 500；中文只在「输出结果」中出现没有问题。
 */
@Service
public class JenkinsService {

    private static final Pattern PRE_BLOCK = Pattern.compile("<pre>([\\s\\S]*?)</pre>");
    /** 连接超时：多线路依次尝试，单线不宜过长 */
    private static final int CONNECT_TIMEOUT = 3_000;
    private static final int READ_TIMEOUT = 30_000;

    /** 列出全部视图及备注的脚本（纯 ASCII，输出行格式 VIEW|视图名|备注） */
    private static final String LIST_VIEWS_SCRIPT =
            "import jenkins.model.Jenkins\n"
            + "Jenkins.instance.getViews().each { v ->\n"
            + "    def d = v.getDescription()\n"
            + "    println \"VIEW|\" + v.getViewName() + \"|\" + (d == null ? \"\" : d)\n"
            + "}";

    private final JenkinsProperties props;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    /** 最近一次连通的线路，优先复用 */
    private volatile String lastGoodBase;

    public JenkinsService(JenkinsProperties props) {
        this.props = props;
        // crumb 与会话绑定，需要携带 cookie
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(new CookieManager());
        }
    }

    /** Jenkins 视图信息 */
    public static class ViewInfo {
        private final String name;
        private final String desc;

        public ViewInfo(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        public String getName() {
            return name;
        }

        public String getDesc() {
            return desc;
        }
    }

    public String getBaseUrl() {
        // 展示/拼视图详情链接用：始终返回配置的真实地址（用户浏览器可达），
        // 不返回隧道回环地址（用户浏览器连不上服务器的回环）
        return props.getBaseUrl() == null ? "" : props.getBaseUrl().replaceAll("/+$", "");
    }

    /** 全部候选线路：配置的 base-url → 中继代理 → 隧道回环线路；上次连通的线路置顶复用 */
    List<String> candidateBaseUrls() {
        List<String> candidates = new ArrayList<>();
        if (props.getBaseUrl() != null && !props.getBaseUrl().trim().isEmpty()) {
            candidates.add(props.getBaseUrl().replaceAll("/+$", ""));
            // 中继线路与配置地址同源（服务器不可达 Jenkins，由在线代理的电脑代发）
            candidates.add(OnesClient.relayBaseOf(props.getBaseUrl().replaceAll("/+$", "")));
        }
        if (props.getTunnelStart() <= props.getTunnelEnd()) {
            for (int i = props.getTunnelStart(); i <= props.getTunnelEnd(); i++) {
                candidates.add("http://127.0.0." + i + ":" + props.getTunnelPort()
                        + (props.getTunnelContext().isEmpty() ? "" : props.getTunnelContext()));
            }
        }
        if (lastGoodBase != null) {
            candidates.remove(lastGoodBase);
            candidates.add(0, lastGoodBase);
        }
        return candidates;
    }

    /** 多线路调用：连接层失败自动切换下一条线路；业务错误（HTTP 状态/凭据问题）立即抛出 */
    private <T> T forAnyBase(BaseCall<T> call) {
        List<String> candidates = candidateBaseUrls();
        IllegalStateException last = null;
        for (String base : candidates) {
            try {
                T result = call.run(base);
                lastGoodBase = base;
                return result;
            } catch (IllegalStateException e) {
                throw e; // HTTP 状态类错误与线路无关，不切换
            } catch (Exception e) {
                last = new IllegalStateException("Jenkins 连接失败：" + e.getMessage(), e);
            }
        }
        throw last != null ? last : new IllegalStateException("未配置 Jenkins 连接（jenkins.base-url）");
    }

    private interface BaseCall<T> {
        T run(String base) throws Exception;
    }

    public List<ViewInfo> fetchViews(String username, String credential) {
        String output = executeScript(username, credential, LIST_VIEWS_SCRIPT);
        List<ViewInfo> views = new ArrayList<>();
        for (String line : output.split("\r?\n")) {
            if (!line.startsWith("VIEW|")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length >= 3) {
                String desc = parts[2].trim();
                views.add(new ViewInfo(parts[1].trim(), desc.isEmpty() ? null : desc));
            }
        }
        return views;
    }

    /**
     * 校验 Jenkins 账号凭据（登录用）：调 /me/api/json，
     * HTTP 200 返回显示名（fullName，空则退回账号名）；
     * 401/403 视为凭据错误返回 null。
     */
    public String authenticate(String username, String credential) {
        return forAnyBase(base -> {
            HttpURLConnection conn = RelayConnection.open(base + "/me/api/json");
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Authorization", basicAuth(username, credential));

            int status = conn.getResponseCode();
            if (status == 200) {
                String body = readStream(conn.getInputStream());
                JsonNode node = jsonMapper.readTree(body);
                String fullName = node.path("fullName").asText("");
                return fullName.trim().isEmpty() ? username : fullName.trim();
            }
            if (status == 401 || status == 403) {
                return null;
            }
            // 消费并关闭流，避免连接泄漏
            readStream(status < 400 ? conn.getInputStream() : conn.getErrorStream());
            throw new IllegalStateException("Jenkins 账号校验失败（HTTP " + status + "）");
        });
    }

    /** 执行 Groovy 脚本，返回结果 <pre> 块中的纯文本 */
    private String executeScript(String username, String credential, String script) {
        return forAnyBase(base -> {
            String crumb = fetchCrumb(base, username, credential);

            HttpURLConnection conn = RelayConnection.open(base + "/script");
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", basicAuth(username, credential));
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            if (crumb != null) {
                conn.setRequestProperty("Jenkins-Crumb", crumb);
            }

            String body = "script=" + java.net.URLEncoder.encode(script, "UTF-8")
                    + "&Submit=Run";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            String html = readStream(
                    status < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (status != 200) {
                throw new IllegalStateException("Jenkins 脚本执行失败（HTTP " + status + "）");
            }

            // 结果在响应 HTML 的最后一个 <pre> 块中
            Matcher matcher = PRE_BLOCK.matcher(html);
            String last = null;
            while (matcher.find()) {
                last = matcher.group(1);
            }
            return last == null ? "" : unescapeHtml(last);
        });
    }

    /** 获取 CSRF crumb；未启用 CSRF 时返回 null */
    private String fetchCrumb(String base, String username, String credential) throws Exception {
        HttpURLConnection conn = RelayConnection.open(base + "/crumbIssuer/api/json");
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Authorization", basicAuth(username, credential));

        int status = conn.getResponseCode();
        if (status == 404) {
            return null; // 未启用 CSRF
        }
        String body = readStream(status < 400 ? conn.getInputStream() : conn.getErrorStream());
        if (status != 200) {
            throw new IllegalStateException("获取 Jenkins crumb 失败（HTTP " + status + "），请检查账号凭据");
        }
        JsonNode node = jsonMapper.readTree(body);
        return node.path("crumb").asText(null);
    }

    /** 拼接 Basic 认证头（密码或 API Token 通用） */
    private String basicAuth(String username, String credential) {
        String token = username + ":" + credential;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private String readStream(java.io.InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Jenkins 输出中的基础 HTML 实体还原 */
    private static String unescapeHtml(String text) {
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
