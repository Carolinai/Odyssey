package org.odyssey.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odyssey.config.OdysseyProperties;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隧道服务测试：槽位分配/复用、状态探测、初始化脚本内容。
 */
class TunnelServiceTest {

    @TempDir
    Path tempDir;

    private TunnelService service() {
        OdysseyProperties props = new OdysseyProperties();
        props.setDataDir(tempDir.toString());
        return new TunnelService(props);
    }

    private Path keysFile() {
        return tempDir.resolve("tunnel").resolve("authorized_keys");
    }

    @Test
    void 登记用户_从最小空闲槽位分配() {
        TunnelService service = service();
        assertEquals(2, service.enroll("alice").getSlot());
        assertEquals(3, service.enroll("bob").getSlot());
    }

    @Test
    void 重复登记_槽位不变_密钥轮换() throws Exception {
        TunnelService service = service();
        TunnelService.Enrollment first = service.enroll("alice");
        TunnelService.Enrollment second = service.enroll("alice");
        assertEquals(first.getSlot(), second.getSlot());
        assertFalse(first.getPrivateKey().equals(second.getPrivateKey()), "重新登记应换新密钥");

        // 文件里该用户仍只有一行
        assertEquals(1, countLines("alice"));
    }

    @Test
    void 删除登记后_槽位回收再分配() {
        TunnelService service = service();
        service.enroll("alice"); // 2
        service.enroll("bob");   // 3
        service.remove("alice");
        assertEquals(2, service.enroll("carol").getSlot()); // 复用 alice 的 2
    }

    @Test
    void 状态探测_在线与离线() throws Exception {
        TunnelService service = service();
        TunnelService.Enrollment enrollment = service.enroll("alice");

        TunnelService.Status offline = service.statusOf("alice");
        assertTrue(offline.installed);
        assertFalse(offline.online);

        // 在 127.0.0.{slot}:18081 起一个监听模拟 sshd
        try (ServerSocket socket = new ServerSocket(18081, 1,
                java.net.InetAddress.getByName("127.0.0." + enrollment.getSlot()))) {
            TunnelService.Status online = service.statusOf("alice");
            assertTrue(online.online, "槽位端口有监听时应判定在线");
        }
        assertFalse(service.statusOf("nobody").installed, "未登记用户");
    }

    @Test
    void 非法用户名_拒绝登记() {
        assertThrows(IllegalArgumentException.class, () -> service().enroll("bad name"));
        assertThrows(IllegalArgumentException.class, () -> service().enroll("a\nb"));
    }

    @Test
    void 初始化脚本_含私钥与槽位线路_可重复运行() {
        TunnelService service = service();
        TunnelService.Enrollment enrollment = service.enroll("alice");
        String script = service.buildInitScript(enrollment, "alice");

        // CRLF 行尾（Windows bat）
        assertTrue(script.contains("\r\n"), "脚本应为 CRLF 行尾");
        // 私钥以 base64 内嵌 + certutil 解码（保证 LF 行尾不被 cmd 的 CRLF 污染）
        assertTrue(script.contains("certutil -f -decode \"%OD%\\id_ed25519.b64\" \"%OD%\\id_ed25519\" >nul"),
                "私钥应由 certutil 解码写出");
        String keyB64 = Base64.getEncoder().encodeToString(
                enrollment.getPrivateKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(script.contains(keyB64.substring(0, 64)), "脚本应内嵌私钥 base64");
        // 内嵌启动脚本（base64）+ certutil 解码
        assertTrue(script.contains("start-tunnel.bat.b64"));
        assertTrue(script.contains("certutil -f -decode"));
        // 专用受限账号（ssh 命令行在内嵌 base64 里，用户名以环境变量拼接可见于启动脚本）
        assertTrue(service.buildStartBat(enrollment)
                .contains("-R 127.0.0." + enrollment.getSlot() + ":18081:124.70.159.198:8081"));
        assertTrue(service.buildStartBat(enrollment).contains("odyssey-tunnel@"));
        // 启动项（注册表 odyssey:// 协议因公司组策略禁用而移除）
        assertTrue(script.contains("Startup\\odyssey-tunnel.vbs"));
        assertFalse(script.contains("reg add"), "不应包含注册表操作（组策略禁用）");
    }

    private long countLines(String username) throws Exception {
        List<String> lines = Files.readAllLines(keysFile());
        return lines.stream().filter(l -> l.endsWith("odyssey-tunnel:" + username)).count();
    }
}
