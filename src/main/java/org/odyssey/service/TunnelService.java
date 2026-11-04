package org.odyssey.service;

import org.odyssey.config.OdysseyProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 隧道/中继登记服务：服务器出网受限，部分内网目标依赖「各用户电脑代发」。
 *
 * 主线路是 HTTP 中继（见 RelayService）：用户领取初始化脚本（内嵌专属 ed25519 私钥与
 * 中继令牌），运行后其电脑常驻中继代理（org.odyssey.agent.RelayAgentMain），定期向
 * 服务器报到并代发上游请求。之所以用 HTTP 轮询而非 SSH 长连接：公司零信任客户端
 * （aTrust）在内核层劫持出站 SSH 且会周期性掐断长连接，短 HTTP 请求则不受影响。
 *
 * SSH 反向隧道降级为手动兜底：每位用户仍分配专属槽位，运行脚本后可在同一条 SSH
 * 连接上携带三条转发（127.0.0.{槽位}:18081/18082/18083 → Jenkins/ONES/AI 网关），
 * 由各客户端作为最后的候选线路尝试。authorized_keys 只存公钥本体（原因见 enroll
 * 注释），端口绑定白名单由宿主机 sshd_config 的 Match User odyssey-tunnel 统一限制。
 *
 * authorized_keys 文件由本服务维护（数据目录下），宿主机 systemd path 单元
 * （odyssey-tunnel-keys.path）在文件变化时同步给系统账号 odyssey-tunnel。
 */
@Service
public class TunnelService {

    /** 一次隧道登记结果：私钥只在此刻返回给用户本人，服务端不落盘保存 */
    public static class Enrollment {
        private final int slot;              // 槽位（对应 127.0.0.{slot}）
        private final String privateKey;     // OpenSSH 私钥 PEM 文本

        public Enrollment(int slot, String privateKey) {
            this.slot = slot;
            this.privateKey = privateKey;
        }

        public int getSlot() {
            return slot;
        }

        public String getPrivateKey() {
            return privateKey;
        }
    }

    /** 隧道状态 */
    public static class Status {
        public final boolean installed; // 是否已登记（服务器上有该用户的公钥）
        public final boolean online;    // 该用户的隧道当前是否在线

        public Status(boolean installed, boolean online) {
            this.installed = installed;
            this.online = online;
        }
    }

    private static final Pattern SLOT_PATTERN =
            Pattern.compile("permitlisten=\"127\\.0\\.0\\.(\\d+):\\d+\"");
    /** 新格式槽位标记：授权行不再写 permitlisten（见 enroll 注释），槽位记录在用户标签前 */
    private static final Pattern SLOT_TAG_PATTERN = Pattern.compile("odyssey-slot:(\\d+)");
    private static final String USER_TAG_PREFIX = "odyssey-tunnel:";

    private final OdysseyProperties props;
    private final int slotMin;
    private final int slotMax;
    private final int listenPort;
    private final String sshHost;
    private final int sshPort;
    private final String sshUser;
    private final String targetHost;
    private final int targetPort;
    /** ONES 备用线路：同一 SSH 会话的第二条转发（127.0.0.{槽位}:{onesListenPort} → ONES） */
    private final int onesListenPort;
    private final String onesTargetHost;
    private final int onesTargetPort;
    /** AI 网关备用线路：同一 SSH 会话的第三条转发（127.0.0.{槽位}:{aiListenPort} → AI 网关） */
    private final int aiListenPort;
    private final String aiTargetHost;
    private final int aiTargetPort;

    public TunnelService(OdysseyProperties props) {
        this.props = props;
        this.slotMin = intProp("odyssey.tunnel.slot-min", 2);
        this.slotMax = intProp("odyssey.tunnel.slot-max", 21);
        this.listenPort = intProp("odyssey.tunnel.listen-port", 18081);
        this.sshHost = strProp("odyssey.tunnel.ssh-host", "127.0.0.1");
        this.sshPort = intProp("odyssey.tunnel.ssh-port", 22);
        this.sshUser = strProp("odyssey.tunnel.ssh-user", "odyssey-tunnel");
        this.targetHost = strProp("odyssey.tunnel.target-host", "124.70.159.198");
        this.targetPort = intProp("odyssey.tunnel.target-port", 8081);
        this.onesListenPort = intProp("odyssey.tunnel.ones-listen-port", 18082);
        this.onesTargetHost = strProp("odyssey.tunnel.ones-target-host", "10.10.191.213");
        this.onesTargetPort = intProp("odyssey.tunnel.ones-target-port", 80);
        this.aiListenPort = intProp("odyssey.tunnel.ai-listen-port", 18083);
        this.aiTargetHost = strProp("odyssey.tunnel.ai-target-host", "aikeyhub.gateway.mdzy");
        this.aiTargetPort = intProp("odyssey.tunnel.ai-target-port", 80);
    }

    private static int intProp(String key, int def) {
        String v = System.getProperty(key, System.getenv().get(envKey(key)));
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String strProp(String key, String def) {
        String v = System.getProperty(key, System.getenv().get(envKey(key)));
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    /** 属性键转环境变量名：odyssey.tunnel.ssh-host → ODYSSEY_TUNNEL_SSH_HOST（. 与 - 都换成 _） */
    private static String envKey(String key) {
        return key.toUpperCase().replace('.', '_').replace('-', '_');
    }

    /** 当前登记用户 → 槽位；未登记返回 -1 */
    public synchronized int slotOf(String username) {
        try {
            List<String> lines = readLines();
            for (String line : lines) {
                if (taggedUser(line).equals(username)) {
                    return slotOfLine(line);
                }
            }
            return -1;
        } catch (IOException e) {
            throw new UncheckedIOException("读取隧道授权文件失败", e);
        }
    }

    /** 隧道状态探测：established 与否看服务器回环槽位端口是否有 sshd 在听 */
    public Status statusOf(String username) {
        int slot = slotOf(username);
        if (slot < 0) {
            return new Status(false, false);
        }
        return new Status(true, probe("127.0.0." + slot, listenPort));
    }

    /** 登记用户：已有槽位则原槽位换新密钥（旧私钥作废），否则分配最小空闲槽位 */
    public synchronized Enrollment enroll(String username) {
        if (username == null || !username.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("用户名含非法字符");
        }
        List<String> lines;
        try {
            lines = new ArrayList<>(readLines());
        } catch (IOException e) {
            throw new UncheckedIOException("读取隧道授权文件失败", e);
        }

        // 找现有槽位或最小空闲槽位
        int existing = -1;
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (String line : lines) {
            int slot = slotOfLine(line);
            if (slot > 0) {
                used.add(slot);
            }
            if (taggedUser(line).equals(username)) {
                existing = slot;
            }
        }
        int slot = existing;
        if (slot < 0) {
            for (int s = slotMin; s <= slotMax; s++) {
                if (!used.contains(s)) {
                    slot = s;
                    break;
                }
            }
        }
        if (slot < 0) {
            throw new IllegalStateException("隧道槽位已用完（" + (slotMax - slotMin + 1) + " 个），请联系管理员");
        }

        SshKeyGen.KeyPair key = SshKeyGen.generate(USER_TAG_PREFIX + username);
        // 端口绑定限制由 sshd_config 的 Match User odyssey-tunnel → PermitListen 白名单统一兜底
        // （授权行不能写多个 permitlisten：OpenSSH 9.9p1 下第二个选项起静默解析失败、整行失效，
        //   实测 2026-11-03，三种写法均复现；单选项又表达不了三个端口，故只能省略。
        //   槽位号写在用户标签前，供槽位分配/状态探测解析——行尾仍以用户标签结束）
        String newLine = key.getOpenSshPublicKey() + " odyssey-slot:" + slot
                + " " + USER_TAG_PREFIX + username;

        // 替换该用户旧行或追加
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (taggedUser(lines.get(i)).equals(username)) {
                lines.set(i, newLine);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lines.add(newLine);
        }
        writeLines(lines);
        return new Enrollment(slot, key.getOpenSshPrivateKey());
    }

    /** 删除登记（槽位回收） */
    public synchronized void remove(String username) {
        try {
            List<String> lines = new ArrayList<>(readLines());
            boolean changed = lines.removeIf(line -> taggedUser(line).equals(username));
            if (changed) {
                writeLines(lines);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取隧道授权文件失败", e);
        }
    }

    /** 中继代理启动脚本（安装器将其 base64 内嵌）：结束旧代理后循环拉起，异常退出自动重启 */
    String buildStartRelayBat(String serverUrl, String relayToken, String allowHosts) {
        return String.join("\r\n",
                "@echo off",
                "set \"OD=%USERPROFILE%\\.odyssey-tunnel\"",
                "rem 结束旧的中继代理（按命令行特征匹配，不影响其他 Java 程序），并停掉旧 SSH 隧道",
                "powershell -NoProfile -Command \"Get-CimInstance Win32_Process"
                        + " | Where-Object {($_.Name -eq 'java.exe') -and ($_.CommandLine -match 'relay-agent')}"
                        + " | ForEach-Object {Stop-Process -Id $_.ProcessId -Force}\" >nul 2>&1",
                "taskkill /F /IM ssh.exe >nul 2>&1",
                ":loop",
                "java -jar \"%OD%\\relay-agent.jar\" --server " + serverUrl
                        + " --token " + relayToken + " --allow " + allowHosts
                        + " >> \"%OD%\\relay.log\" 2>&1",
                "rem 代理退出（异常/网络切换）10 秒后自动重启",
                "ping -n 11 127.0.0.1 >nul",
                "goto loop") + "\r\n";
    }

    /**
     * 生成 Windows 初始化脚本：下载中继代理 jar、装启动脚本（SSH 反向隧道已由中继方案
     * 取代——公司零信任客户端 aTrust 会掐断长连 SSH，见 RelayService 类注释；
     * 私钥仍保留写入，供需要时手动建立 SSH 兜底隧道）、入启动项并立即启动。
     *
     * @param relayToken 该用户的中继令牌（RelayService.issueToken 签发）
     * @param serverUrl  本应用的访问地址（代理轮询目标），形如 http://10.0.106.110:8787
     */
    public String buildInitScript(Enrollment enrollment, String username,
            String relayToken, String serverUrl) {
        String key = enrollment.getPrivateKey();
        // 代理可代发的目标：与三条 SSH 转发的目标一致（主机维度白名单，代理侧校验）
        String allowHosts = String.join(",",
                java.util.Arrays.asList(targetHost, onesTargetHost, aiTargetHost));

        String startBat = buildStartRelayBat(serverUrl, relayToken, allowHosts);

        // 守护脚本：静默拉起 start-relay.bat（重复启动会先结束旧代理再拉起新的）
        // VBS 引号转义："""" 是一个字面双引号；路径里的反斜杠无需转义，且 Run 语句必须单行
        String checkVbs = String.join("\r\n",
                "' Odyssey relay guard: silently start (old agent killed by start-relay.bat)",
                "Set sh = CreateObject(\"WScript.Shell\")",
                "sh.Run \"\"\"\" & sh.ExpandEnvironmentStrings(\"%USERPROFILE%\")"
                        + " & \"\\.odyssey-tunnel\\start-relay.bat\"\"\", 0, False");

        List<String> script = new ArrayList<>();
        script.add("@echo off");
        script.add("rem Odyssey 中继代理安装脚本 for user: " + username);
        script.add("rem 作用：安装并常驻中继代理（代发服务器对 ONES/Jenkins/AI 网关的请求）；可重复运行（会换新令牌并先结束旧代理）");
        script.add("set \"OD=%USERPROFILE%\\.odyssey-tunnel\"");
        script.add("if not exist \"%OD%\" mkdir \"%OD%\"");
        // 1. 下载中继代理 jar（应用自身分发，零第三方依赖）
        script.add("curl -f -s -S -o \"%OD%\\relay-agent.jar\" \"" + serverUrl + "/api/relay/agent.jar\"");
        script.add("if errorlevel 1 (echo 代理下载失败，请检查网络后重试 & exit /b 1)");
        // 2. SSH 私钥（保留写入：SSH 反向隧道作为手动兜底手段）
        script.add("> \"%OD%\\id_ed25519.b64\" (");
        for (String line : wrap64(Base64.getEncoder().encodeToString(
                key.getBytes(StandardCharsets.UTF_8)))) {
            script.add("echo " + line);
        }
        script.add(")");
        script.add("certutil -f -decode \"%OD%\\id_ed25519.b64\" \"%OD%\\id_ed25519\" >nul");
        script.add("del \"%OD%\\id_ed25519.b64\"");
        script.add("echo " + hostKeyLine() + " > \"%OD%\\known_hosts\"");
        // 3. 启动脚本与守护脚本以 base64 内嵌（certutil 解码，避免 bat 转义问题）
        script.add("> \"%OD%\\start-relay.bat.b64\" (");
        for (String line : wrap64(Base64.getEncoder().encodeToString(
                startBat.getBytes(StandardCharsets.UTF_8)))) {
            script.add("echo " + line);
        }
        script.add(")");
        script.add("certutil -f -decode \"%OD%\\start-relay.bat.b64\" \"%OD%\\start-relay.bat\" >nul");
        script.add("del \"%OD%\\start-relay.bat.b64\"");
        script.add("> \"%OD%\\check-relay.vbs.b64\" (");
        for (String line : wrap64(Base64.getEncoder().encodeToString(
                checkVbs.getBytes(StandardCharsets.UTF_8)))) {
            script.add("echo " + line);
        }
        script.add(")");
        script.add("certutil -f -decode \"%OD%\\check-relay.vbs.b64\" \"%OD%\\check-relay.vbs\" >nul");
        script.add("del \"%OD%\\check-relay.vbs.b64\"");
        // 4. Java 环境预检（仅提示，不阻断——代理日志会记录启动失败原因）
        script.add("where java >nul 2>&1 || echo [提示] 未找到 java 命令，中继代理无法启动，请安装 Java 并加入 PATH");
        // 5. 启动项：替换旧隧道守护为新的中继守护
        script.add("del \"%APPDATA%\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\odyssey-tunnel.vbs\" >nul 2>&1");
        script.add("copy /y \"%OD%\\check-relay.vbs\" \"%APPDATA%\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\odyssey-relay.vbs\" >nul");
        // 6. 立即启动（start-relay.bat 内部会先结束旧代理与旧 ssh）
        script.add("wscript \"%OD%\\check-relay.vbs\"");
        script.add("echo Odyssey relay agent installed.");
        script.add("exit /b 0");

        return String.join("\r\n", script) + "\r\n";
    }

    /** known_hosts 行：部署时由 ssh-keyscan 生成，经系统属性注入；未配置则留空（脚本用 accept 首连） */
    private String hostKeyLine() {
        String v = System.getProperty("odyssey.tunnel.host-key", System.getenv("ODYSSEY_TUNNEL_HOST_KEY"));
        return v == null || v.trim().isEmpty()
                ? "10.0.106.110 ssh-ed25519 placeholder" : v.trim();
    }

    // ---------- authorized_keys 文件 ----------

    private Path authorizedKeysFile() {
        String path = System.getProperty("odyssey.tunnel.authorized-keys",
                System.getenv("ODYSSEY_TUNNEL_AUTHORIZED_KEYS"));
        if (path == null || path.trim().isEmpty()) {
            return java.nio.file.Paths.get(props.getDataDir(), "tunnel", "authorized_keys");
        }
        return java.nio.file.Paths.get(path);
    }

    private List<String> readLines() throws IOException {
        Path file = authorizedKeysFile();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private void writeLines(List<String> lines) {
        try {
            Path file = authorizedKeysFile();
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "tunnel-keys-", ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写入隧道授权文件失败", e);
        }
    }

    private static int slotOfLine(String line) {
        Matcher m = SLOT_PATTERN.matcher(line); // 旧格式：permitlisten 选项
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = SLOT_TAG_PATTERN.matcher(line); // 新格式：行内 odyssey-slot:N 标记
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String taggedUser(String line) {
        int idx = line.indexOf(USER_TAG_PREFIX);
        return idx < 0 ? "" : line.substring(idx + USER_TAG_PREFIX.length()).trim();
    }

    /** 探测 TCP 端口是否可连（快速超时） */
    private static boolean probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> wrap64(String s) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < s.length(); i += 64) {
            lines.add(s.substring(i, Math.min(s.length(), i + 64)));
        }
        return lines;
    }
}
