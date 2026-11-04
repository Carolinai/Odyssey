package org.odyssey.api;

import org.odyssey.service.RelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * 中继代理接口：代理与服务器之间用行式 TSV + base64 通信（见 RelayService 类注释）。
 * 不用 JSON 是为了代理端零依赖（Java 8 标准库即可实现）。
 *
 * 请求体（POST /api/relay/poll）：
 *   AGENT\t<令牌>
 *   RESULT\t<任务id>\t<HTTP状态>\t<响应头b64>\t<响应体b64>     （可多行，上次领走的任务结果）
 *
 * 响应体：
 *   JOB\t<任务id>\t<方法>\t<目标URL>\t<请求头b64>\t<请求体b64>   （可多行，本次领走的任务）
 */
@Controller
public class RelayController {

    private static final Logger log = LoggerFactory.getLogger(RelayController.class);

    private final RelayService relay;

    public RelayController(RelayService relay) {
        this.relay = relay;
    }

    @PostMapping(value = "/api/relay/poll", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> poll(@RequestBody String body) {
        String token = null;
        List<Map<String, Object>> results = new ArrayList<>();
        for (String line : body.split("\r?\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if ("AGENT".equals(f[0]) && f.length >= 2) {
                token = f[1];
            } else if ("RESULT".equals(f[0]) && f.length >= 5) {
                Map<String, Object> r = new HashMap<>();
                r.put("id", f[1]);
                r.put("status", parseIntSafe(f[2]));
                r.put("headers", f[3]);
                r.put("body", f[4]);
                r.put("encoding", f.length >= 6 ? f[5] : "");
                results.add(r);
            }
        }
        List<Map<String, Object>> jobs = relay.poll(token, results);
        if (jobs == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unknown agent token");
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> job : jobs) {
            out.append("JOB\t").append(job.get("id")).append('\t').append(job.get("method"))
                    .append('\t').append(job.get("url")).append('\t')
                    .append(job.get("headers")).append('\t').append(job.get("body")).append('\n');
        }
        return ResponseEntity.ok(out.toString());
    }

    /**
     * 中继代理 jar：从应用自身 classpath 抽取 org.odyssey.agent 包的 class 动态打包
     * （代理零第三方依赖，与主应用同一个构建产物，无需单独分发）。
     */
    @GetMapping("/api/relay/agent.jar")
    public ResponseEntity<byte[]> agentJar() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "org.odyssey.agent.RelayAgentMain");
        try (JarOutputStream jar = new JarOutputStream(buf, manifest)) {
            for (String name : agentClassNames()) {
                jar.putNextEntry(new JarEntry(name));
                try (InputStream in = RelayController.class.getResourceAsStream("/" + name)) {
                    if (in == null) {
                        throw new IOException("代理类缺失：" + name);
                    }
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        jar.write(b, 0, n);
                    }
                }
                jar.closeEntry();
            }
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"odyssey-relay-agent.jar\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(buf.toByteArray());
    }

    /** 代理包内的类：主类与编译产生的内部类/辅助类 */
    private static List<String> agentClassNames() {
        List<String> names = new ArrayList<>();
        for (String base : new String[]{"RelayAgentMain"}) {
            names.add("org/odyssey/agent/" + base + ".class");
            for (int i = 1; i <= 9; i++) {
                String nested = "org/odyssey/agent/" + base + "$" + i + ".class";
                if (RelayController.class.getResourceAsStream("/" + nested) != null) {
                    names.add(nested);
                }
            }
        }
        return names;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 502;
        }
    }
}
