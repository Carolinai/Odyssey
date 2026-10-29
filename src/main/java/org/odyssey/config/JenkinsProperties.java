package org.odyssey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jenkins 连接配置。
 * 不含账号：Jenkins 调用一律用当前登录用户自己的凭据（登录时校验并存在会话里）。
 */
@ConfigurationProperties(prefix = "jenkins")
public class JenkinsProperties {

    /** Jenkins 基础地址（含 context path），如 http://x.x.x.x:8081/jk */
    private String baseUrl;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
