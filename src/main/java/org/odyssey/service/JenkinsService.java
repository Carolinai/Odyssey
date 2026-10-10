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
 * 注意：提交给 Jenkins 的脚本必须为纯 ASCII——该版本 Jenkins 对表单中的
 * 非 ASCII 字符解析会直接 500；中文只在「输出结果」中出现没有问题。
 */
@Service
public class JenkinsService {

    private static final Pattern PRE_BLOCK = Pattern.compile("<pre>([\\s\\S]*?)</pre>");
    private static final int CONNECT_TIMEOUT = 10_000;
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
        return props.getBaseUrl() == null ? "" : props.getBaseUrl().replaceAll("/+$", "");
    }

    public List<ViewInfo> fetchViews() {
        String output = executeScript(LIST_VIEWS_SCRIPT);
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

    /** 执行 Groovy 脚本，返回结果 <pre> 块中的纯文本 */
    private String executeScript(String script) {
        if (props.getBaseUrl() == null || props.getBaseUrl().trim().isEmpty()) {
            throw new IllegalStateException("未配置 Jenkins 连接（jenkins.base-url）");
        }
        String base = props.getBaseUrl().replaceAll("/+$", "");
        try {
            String crumb = fetchCrumb(base);

            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/script").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", basicAuth());
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
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jenkins 连接失败：" + e.getMessage(), e);
        }
    }

    /** 获取 CSRF crumb；未启用 CSRF 时返回 null */
    private String fetchCrumb(String base) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/crumbIssuer/api/json").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Authorization", basicAuth());

        int status = conn.getResponseCode();
        if (status == 404) {
            return null; // 未启用 CSRF
        }
        String body = readStream(status < 400 ? conn.getInputStream() : conn.getErrorStream());
        if (status != 200) {
            throw new IllegalStateException("获取 Jenkins crumb 失败（HTTP " + status + "），请检查账号配置");
        }
        JsonNode node = jsonMapper.readTree(body);
        return node.path("crumb").asText(null);
    }

    private String basicAuth() {
        String token = props.getUsername() + ":" + props.getPassword();
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
