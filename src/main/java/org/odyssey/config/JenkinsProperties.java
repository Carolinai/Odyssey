package org.odyssey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jenkins 连接配置。
 * 不含账号：Jenkins 调用一律用当前登录用户自己的凭据（登录时校验并存在会话里）。
 */
@ConfigurationProperties(prefix = "jenkins")
public class JenkinsProperties {

    /** Jenkins 基础地址（含 context path），如 http://x.x.x.x:8081/jk；作为首选线路 */
    private String baseUrl;

    /** 隧道候选线路：服务器回环 127.0.0.{start}~{end} 的 {port} 端口经 SSH 反向隧道转发到 Jenkins；
     *  start > end 表示不启用隧道线路 */
    private int tunnelStart = 2;
    private int tunnelEnd = 21;
    private int tunnelPort = 18081;

    /** 隧道转发目标 Jenkins 的 context path（隧道只转发 TCP，路径由应用侧拼上） */
    private String tunnelContext = "/jk";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTunnelStart() {
        return tunnelStart;
    }

    public void setTunnelStart(int tunnelStart) {
        this.tunnelStart = tunnelStart;
    }

    public int getTunnelEnd() {
        return tunnelEnd;
    }

    public void setTunnelEnd(int tunnelEnd) {
        this.tunnelEnd = tunnelEnd;
    }

    public int getTunnelPort() {
        return tunnelPort;
    }

    public void setTunnelPort(int tunnelPort) {
        this.tunnelPort = tunnelPort;
    }

    public String getTunnelContext() {
        return tunnelContext == null ? "" : tunnelContext;
    }

    public void setTunnelContext(String tunnelContext) {
        this.tunnelContext = tunnelContext;
    }
}
