package org.odyssey.service;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 生成 OpenSSH 格式的 ed25519 密钥对（隧道专用）。
 * 私钥采用 openssh-key-v1 未加密容器格式；用 JDK 自带的 Ed25519 不可用（Java 8），
 * 故用项目已有的 BouncyCastle 生成密钥材料，按 OpenSSH 线格式手工编码。
 */
public final class SshKeyGen {

    private static final String KEY_TYPE = "ssh-ed25519";
    private static final byte[] MAGIC = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);

    private SshKeyGen() {
    }

    /** 一对密钥：openSshPublicKey 形如 ssh-ed25519 AAA...；openSshPrivateKey 为 PEM 文本 */
    public static class KeyPair {
        private final String openSshPublicKey;
        private final String openSshPrivateKey;

        public KeyPair(String openSshPublicKey, String openSshPrivateKey) {
            this.openSshPublicKey = openSshPublicKey;
            this.openSshPrivateKey = openSshPrivateKey;
        }

        public String getOpenSshPublicKey() {
            return openSshPublicKey;
        }

        public String getOpenSshPrivateKey() {
            return openSshPrivateKey;
        }
    }

    public static KeyPair generate(String comment) {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(new SecureRandom());
        Ed25519PublicKeyParameters pub = priv.generatePublicKey();
        byte[] pk = pub.getEncoded(); // 32 字节裸公钥
        // SSH 线格式公钥 blob：string("ssh-ed25519") + string(pk)，共 51 字节
        byte[] pubWire = concat(sshString(KEY_TYPE.getBytes(StandardCharsets.US_ASCII)), sshString(pk));
        String publicKey = KEY_TYPE + " " + Base64.getEncoder().encodeToString(pubWire);
        String privateKey = pem(Base64.getEncoder().encodeToString(
                encodePrivateKeyContainer(priv.getEncoded(), pk, pubWire, comment)));
        return new KeyPair(publicKey, privateKey);
    }

    /** openssh-key-v1 未加密私钥容器 */
    private static byte[] encodePrivateKeyContainer(byte[] seed, byte[] pk, byte[] pubWire, String comment) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(body);

            out.write(MAGIC);
            writeString(out, "none");       // cipher
            writeString(out, "none");       // kdf
            writeString(out, new byte[0]);  // kdfoptions
            writeUInt32(out, 1);            // nkeys
            writeString(out, pubWire);      // 公钥 blob（含类型头的完整线格式）
            writeString(out, privateSection(seed, pk, comment));

            return body.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 容器内的私钥段：checkint×2 + 类型 + 公钥 + 私钥(seed+pub) + 注释 + 填充 */
    private static byte[] privateSection(byte[] seed, byte[] pubBlob, String comment) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);

            int check = new SecureRandom().nextInt();
            writeUInt32(out, check);
            writeUInt32(out, check);
            writeString(out, KEY_TYPE);
            writeString(out, pubBlob);
            // 私钥字段为 64 字节：32 字节种子 + 32 字节公钥
            byte[] privFull = new byte[64];
            System.arraycopy(seed, 0, privFull, 0, 32);
            System.arraycopy(pubBlob, 0, privFull, 32, 32);
            writeString(out, privFull);
            writeString(out, comment.getBytes(StandardCharsets.UTF_8));

            // 填充 1,2,3,... 使私钥段整体 8 字节对齐
            int pad = 1;
            while (buf.size() % 8 != 0) {
                out.write(pad++);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** SSH 线格式 string：uint32 长度 + 内容 */
    private static byte[] sshString(byte[] content) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            writeString(out, content);
            return buf.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        writeString(out, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeString(DataOutputStream out, byte[] content) throws IOException {
        writeUInt32(out, content.length);
        out.write(content);
    }

    private static void writeUInt32(DataOutputStream out, int value) throws IOException {
        out.writeInt(value);
    }

    /** 64 字符换行的 PEM 文本 */
    private static String pem(String base64) {
        StringBuilder sb = new StringBuilder("-----BEGIN OPENSSH PRIVATE KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(base64.length(), i + 64)).append('\n');
        }
        sb.append("-----END OPENSSH PRIVATE KEY-----\n");
        return sb.toString();
    }
}
