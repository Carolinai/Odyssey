package org.odyssey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jenkins 连接配置。
 */
@ConfigurationProperties(prefix = "jenkins")
public class JenkinsProperties {

    /** Jenkins 基础地址（含 context path），如 http://x.x.x.x:8081/jk */
    private String baseUrl;

    private String username;

    private String password;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
