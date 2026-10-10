package org.odyssey.api;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.util.io.pem.PemReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;

import javax.crypto.Cipher;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RSA 解密接口测试：用内置私钥对应的公钥走通「加密→解密」闭环及错误分支 */
@SpringBootTest
@AutoConfigureMockMvc
class DecryptControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("odyssey.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private MockMvc mvc;

    /** 从内置私钥内容派生公钥（模数 + 公开指数） */
    private PublicKey publicKey() throws Exception {
        try (PemReader reader = new PemReader(new StringReader(DecryptController.PRIVATE_KEY_PEM))) {
            org.bouncycastle.util.io.pem.PemObject pem = reader.readPemObject();
            PrivateKeyInfo info = PrivateKeyInfo.getInstance(
                    org.bouncycastle.asn1.ASN1Sequence.getInstance(pem.getContent()));
            RSAPrivateKey rsa = RSAPrivateKey.getInstance(info.parsePrivateKey());
            java.security.spec.RSAPublicKeySpec spec =
                    new java.security.spec.RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent());
            return java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
        }
    }

    private String encrypt(PublicKey pub, String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pub);
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 解密闭环_密文反解出明文() throws Exception {
        String cipherBase64 = encrypt(publicKey(), "P@ssw0rd明文");

        mvc.perform(post("/api/decrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cipher\": \"" + cipherBase64 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plain").value("P@ssw0rd明文"));
    }

    @Test
    void 密文为空_返回400() throws Exception {
        mvc.perform(post("/api/decrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cipher\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 密文非法_返回400() throws Exception {
        mvc.perform(post("/api/decrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cipher\": \"!!!不是base64!!!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 密文解不开_返回400() throws Exception {
        // 随机字节不是合法的 PKCS1 块
        byte[] junk = new byte[64];
        new java.security.SecureRandom().nextBytes(junk);

        mvc.perform(post("/api/decrypt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cipher\": \"" + Base64.getEncoder().encodeToString(junk) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
}
