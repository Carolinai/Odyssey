package org.odyssey.api;

import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Cipher;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * RSA 解密接口：私钥内置于代码，页面只传密文即可反解。
 * 密文/明文均不落盘、不写日志。
 */
@RestController
@RequestMapping("/api")
public class DecryptController {

    /** 内置 RSA 私钥（PKCS#1，PEM 格式） */
    public static final String PRIVATE_KEY_PEM =
            "-----BEGIN RSA PRIVATE KEY-----\n"
            + "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAI1SLdt79BIVnFsJIcoFb7l0Ft6Wm15YR6ToC/C6uJ7s5zhNA8X8sLO7RZStAxch0GiR4Qzg2z1qV67MnM/uDs2z6GHTD66z1dICHXY/xG33t45quVLKpvTsvvEwn3W1MvkWbMXsO6cIMuwzraaSI/rZzpSawqOQ5lwRdx9giCUxAgMBAAECgYBk8F+kt2JbUGg/dcRWoY9tbQewsO4EcxWpKbztktCvYxK72fZqAdTMa2SFjxX3/cT4C8otAqZVtZEvXyJaOfUD1EIbR5FFsrwgEmOlrwZ3AG7zSAszQqgdCLGUknCJo4x7lN7Int5LBB+vTFd7wpiOuuY84S0xi9KIGcJTb8RoyQJBAMMDx4t8sp8opVh4wCHOyw417n3sAeVpY6zDerCoFriNbRLc/xI+92z7cd1Mb6rKdIya0OTx0GrKzq4nP1g++bsCQQC5g9wrvstzfI/0GUlTXj7kRHNyQ0jKk9X0/TeKpYA8yRPouqfmO4CoqpoLylZh4m/qMAkwx0h2Zt7iV3hq8igDAkEAoCh3dhAoJlxcRueMa3eI/he+0T3FZOKD0LwOBA1X86N6o9ZJyXNbAu8SWXvP7cUtqNfwnUYXM81xpkdnV5MIxwJBALSuJUCB2bsHxVhzTEQjFVPTCzFtMLcEVyz9dhvV5tjM6lQPfzkwGLWtOs1i6tD47hEDaeAVJ5ERzeBaWtCmOqsCQGaEQ+YNysqQOaC18d91ljb93LGy1bgEevOEP3JtArro2/KfLZMNojfR+pb0tjFuM/3fZhiA6mPE0bHUBcC3tDs=\n"
            + "-----END RSA PRIVATE KEY-----";

    /** 请求体：cipher = Base64 密文 */
    public static class DecryptRequest {
        private String cipher;

        public String getCipher() {
            return cipher;
        }

        public void setCipher(String cipher) {
            this.cipher = cipher;
        }
    }

    @PostMapping("/decrypt")
    public Map<String, String> decrypt(@RequestBody DecryptRequest request) throws Exception {
        if (request.getCipher() == null || request.getCipher().trim().isEmpty()) {
            throw new IllegalArgumentException("请粘贴密文");
        }

        PrivateKey privateKey = loadPrivateKey();

        // 密文允许带换行/空格的 Base64
        byte[] cipherBytes = Base64.getDecoder().decode(
                request.getCipher().replaceAll("\\s+", ""));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] plain;
        try {
            plain = cipher.doFinal(cipherBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("密文无法解密（密文不完整或不是该私钥对应的密文）");
        }

        Map<String, String> result = new HashMap<>();
        result.put("plain", new String(plain, StandardCharsets.UTF_8));
        return result;
    }

    /**
     * 解析内置 PEM 私钥。按内容自动识别结构、不信任 PEM 标签：
     * 有些私钥文件头是「RSA PRIVATE KEY」但内容实际是 PKCS#8，两者不匹配会导致按标签解析失败。
     */
    private PrivateKey loadPrivateKey() throws Exception {
        try (PemReader reader = new PemReader(new StringReader(PRIVATE_KEY_PEM))) {
            PemObject pem = reader.readPemObject();
            byte[] content = pem.getContent();
            PrivateKeyInfo info;
            try {
                // 先按 PKCS#8（PrivateKeyInfo）解析
                info = PrivateKeyInfo.getInstance(ASN1Sequence.getInstance(content));
            } catch (Exception notPkcs8) {
                // 再按 PKCS#1（RSAPrivateKey）解析，包装成 PrivateKeyInfo
                RSAPrivateKey rsa = RSAPrivateKey.getInstance(ASN1Sequence.getInstance(content));
                info = new PrivateKeyInfo(
                        new AlgorithmIdentifier(
                                org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.rsaEncryption,
                                DERNull.INSTANCE),
                        rsa);
            }
            return new JcaPEMKeyConverter().getPrivateKey(info);
        }
    }
}
