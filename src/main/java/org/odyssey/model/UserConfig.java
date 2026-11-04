package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 个人配置（工单助手相关，按登录用户隔离）：ONES Cookie 与 AI 网关配置。
 * 与 {@link GlobalConfig}（远程方式映射、自动同步开关等系统级配置）相对，
 * 本模型的值属于当前登录用户个人，保存在服务器的 <dataDir>/users/&lt;user&gt;.yaml。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class UserConfig {

    /** ONES 平台 Cookie（个人身份，从已登录的 ONES 浏览器获取，过期需手动更新） */
    private String onesCookie;

    /** AI 网关地址（Anthropic 协议），如 http://aikeyhub.gateway.mdzy/api */
    private String aiBaseUrl;

    /** AI 网关 API Key */
    private String aiApiKey;

    /** AI 模型名，如 glm-5.3-flash */
    private String aiModel;

    /** AI 调用间隔（毫秒），批量任务限速防打爆网关；null 视为默认 1000 */
    private Integer aiCallIntervalMs;

    /** AI 单次调用读超时（毫秒）；null 视为默认 180000 */
    private Integer aiTimeoutMs;

    /** AI 调用失败自动重试次数（仅网络异常/5xx/429 重试，4xx 配置类错误不重试）；null 视为 2 */
    private Integer aiMaxRetries;

    /** 工单同步完成后是否自动提炼解决经验；null 视为关闭 */
    private Boolean insightAutoEnabled;

    public String getOnesCookie() {
        return onesCookie;
    }

    public void setOnesCookie(String onesCookie) {
        this.onesCookie = onesCookie;
    }

    public String getAiBaseUrl() {
        return aiBaseUrl;
    }

    public void setAiBaseUrl(String aiBaseUrl) {
        this.aiBaseUrl = aiBaseUrl;
    }

    public String getAiApiKey() {
        return aiApiKey;
    }

    public void setAiApiKey(String aiApiKey) {
        this.aiApiKey = aiApiKey;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public Integer getAiCallIntervalMs() {
        return aiCallIntervalMs;
    }

    public void setAiCallIntervalMs(Integer aiCallIntervalMs) {
        this.aiCallIntervalMs = aiCallIntervalMs;
    }

    public Integer getAiTimeoutMs() {
        return aiTimeoutMs;
    }

    public void setAiTimeoutMs(Integer aiTimeoutMs) {
        this.aiTimeoutMs = aiTimeoutMs;
    }

    public Integer getAiMaxRetries() {
        return aiMaxRetries;
    }

    public void setAiMaxRetries(Integer aiMaxRetries) {
        this.aiMaxRetries = aiMaxRetries;
    }

    public Boolean getInsightAutoEnabled() {
        return insightAutoEnabled;
    }

    public void setInsightAutoEnabled(Boolean insightAutoEnabled) {
        this.insightAutoEnabled = insightAutoEnabled;
    }
}
