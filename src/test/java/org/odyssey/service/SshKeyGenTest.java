package org.odyssey.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenSSH ed25519 密钥生成测试：用本机 ssh-keygen（Windows 自带）做权威校验——
 * ssh-keygen -y 从私钥推导公钥，两条线一致即格式正确。本机没有 ssh-keygen 时跳过。
 */
class SshKeyGenTest {

    @TempDir
    Path tempDir;

    @Test
    void 生成的私钥能被ssh_keygen识别并推导出同一公钥() throws Exception {
        String sshKeygen = "C:\\WINDOWS\\System32\\OpenSSH\\ssh-keygen.exe";
        Assumptions.assumeTrue(Files.exists(Paths.get(sshKeygen)), "本机无 ssh-keygen，跳过");

        SshKeyGen.KeyPair key = SshKeyGen.generate("odyssey-tunnel:tester");

        // 公钥行格式
        assertTrue(key.getOpenSshPublicKey().startsWith("ssh-ed25519 "), "公钥应为 ssh-ed25519 类型");

        // 私钥交给 ssh-keygen -y 推导公钥，与生成的公钥比对
        Path priv = tempDir.resolve("id_ed25519");
        Files.write(priv, key.getOpenSshPrivateKey().getBytes(StandardCharsets.US_ASCII));
        // Windows OpenSSH 校验私钥文件 ACL：去掉继承权限、只留当前用户
        new ProcessBuilder("icacls", priv.toString(), "/inheritance:r",
                "/grant:r", System.getProperty("user.name") + ":F").start().waitFor();

        Process p = new ProcessBuilder(sshKeygen, "-y", "-f", priv.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "ssh-keygen 超时");
        assertEquals(0, p.exitValue(), "ssh-keygen 应能解析私钥，输出：" + output);
        // ssh-keygen -y 输出会附带注释，只比对类型与密钥体
        assertEquals(key.getOpenSshPublicKey(), output.replaceFirst(" [^ ]+$", ""),
                "私钥推导的公钥应与生成的公钥一致");
    }
}
