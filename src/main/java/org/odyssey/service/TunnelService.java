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
 * Jenkins 隧道服务：服务器出网受限，登录校验依赖「各用户电脑建立的 SSH 反向隧道」。
 *
 * 工作方式：每位登录用户首次登录时领取一份专属初始化脚本（内嵌该用户专属 ed25519 私钥），
 * 运行后其电脑与服务器建立反向隧道，把服务器回环 127.0.0.{槽位}:18081 转发到 Jenkins。
 * authorized_keys 中的每个公钥都带 permitlisten 限制——只能绑定自己的槽位，做不了别的。
 *
 * authorized_keys 文件由本服务维护（数据目录下），宿主机 cron 周期同步给系统账号
 * odyssey-tunnel（仅端口转发、无 shell）。
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
        String newLine = "permitlisten=\"127.0.0." + slot + ":" + listenPort + "\" "
                + key.getOpenSshPublicKey() + " " + USER_TAG_PREFIX + username;

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

    /** 隧道启动脚本（安装器会将其 base64 内嵌）：静默常驻，断线/密钥未同步时自动重连 */
    String buildStartBat(Enrollment enrollment) {
        String slotIp = "127.0.0." + enrollment.getSlot();
        return String.join("\r\n",
                "@echo off",
                "set \"OD=%USERPROFILE%\\.odyssey-tunnel\"",
                "if not exist \"%OD%\" mkdir \"%OD%\"",
                ":loop",
                "\"C:\\WINDOWS\\System32\\OpenSSH\\ssh.exe\" -N -R "
                        + slotIp + ":" + listenPort + ":" + targetHost + ":" + targetPort
                        + " -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3"
                        + " -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=\"%OD%\\known_hosts\""
                        + " -i \"%OD%\\id_ed25519\" -p " + sshPort + " " + sshUser + "@" + sshHost,
                "rem 断线或密钥尚未同步时 15 秒后重连；重复实例会因端口被占快速失败，无害",
                "ping -n 16 127.0.0.1 >nul",
                "goto loop") + "\r\n";
    }

    /** 生成 Windows 初始化脚本：装私钥、写启动脚本、注册 odyssey:// 自愈协议、入启动项并立即启动 */
    public String buildInitScript(Enrollment enrollment, String username) {
        String key = enrollment.getPrivateKey();

        String startBat = buildStartBat(enrollment);

        // 守护脚本：静默拉起 start-tunnel.bat（重复启动会因远端端口被占自动退出，无害）
        // VBS 引号转义："""" 是一个字面双引号；路径里的反斜杠无需转义，且 Run 语句必须单行
        String checkVbs = String.join("\r\n",
                "' Odyssey tunnel guard: silently start (duplicate start exits harmless)",
                "Set sh = CreateObject(\"WScript.Shell\")",
                "sh.Run \"\"\"\" & sh.ExpandEnvironmentStrings(\"%USERPROFILE%\")"
                        + " & \"\\.odyssey-tunnel\\start-tunnel.bat\"\"\", 0, False");

        List<String> script = new ArrayList<>();
        script.add("@echo off");
        script.add("rem Odyssey Jenkins tunnel installer for user: " + username);
        script.add("rem 作用：在本机与公司服务器间建立 SSH 反向隧道，供 Odyssey 访问 Jenkins；可重复运行（会换新密钥）");
        script.add("set \"OD=%USERPROFILE%\\.odyssey-tunnel\"");
        script.add("if not exist \"%OD%\" mkdir \"%OD%\"");
        // 1. 写私钥（base64 内嵌 + certutil 解码，保证行尾 LF 不被 cmd 的 CRLF 污染）
        script.add("> \"%OD%\\id_ed25519.b64\" (");
        for (String line : wrap64(Base64.getEncoder().encodeToString(
                key.getBytes(StandardCharsets.UTF_8)))) {
            script.add("echo " + line);
        }
        script.add(")");
        script.add("certutil -f -decode \"%OD%\\id_ed25519.b64\" \"%OD%\\id_ed25519\" >nul");
        script.add("del \"%OD%\\id_ed25519.b64\"");
        // 2. 启动脚本与守护脚本以 base64 内嵌（certutil 解码，避免 bat 转义问题）
        script.add("> \"%OD%\\start-tunnel.bat.b64\" (");
        for (String line : wrap64(Base64.getEncoder().encodeToString(
                startBat.getBytes(StandardCharsets.UTF_8)))) {
            script.add("echo " + line);
        }
        script.add(")");
        script.add("certutil -f -decode \"%OD%\\start-tunnel.bat.b64\" \"%OD%\\start-tunnel.bat\" >nul");
        script.add("del \"%OD%\\start-tunnel.bat.b64\"");
        script.add("> \"%OD%\\check-tunnel.vbs.b64\" (");
        for (String line : wrap64(Base64.getEncoder().encodeToString(
                checkVbs.getBytes(StandardCharsets.UTF_8)))) {
            script.add("echo " + line);
        }
        script.add(")");
        script.add("certutil -f -decode \"%OD%\\check-tunnel.vbs.b64\" \"%OD%\\check-tunnel.vbs\" >nul");
        script.add("del \"%OD%\\check-tunnel.vbs.b64\"");
        // 3. known_hosts 预置服务器指纹（避免首次连接交互确认）
        script.add("echo " + hostKeyLine() + " > \"%OD%\\known_hosts\"");
        // 4. 启动项（覆盖写入）
        script.add("copy /y \"%OD%\\check-tunnel.vbs\" \"%APPDATA%\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\odyssey-tunnel.vbs\" >nul");
        // 5. 立即启动
        // 说明：曾通过 reg add 注册 odyssey:// 协议实现登录页静默自愈，
        // 但公司组策略禁用注册表编辑（DisableRegistryTools），故移除该方式；
        // 隧道中断时由登录页提示手动重跑 start-tunnel.bat
        script.add("wscript \"%OD%\\check-tunnel.vbs\"");
        script.add("echo Odyssey tunnel installed.");
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
        Matcher m = SLOT_PATTERN.matcher(line);
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
