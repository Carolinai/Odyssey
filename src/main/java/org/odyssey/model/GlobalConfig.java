package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局配置（服务级，全站共享）：包含「远程方式 → 唤起链接」映射
 * （如 向日葵 → sunlogin://，唤起走自定义 URL 协议，由访问者本机的
 * 协议注册唤起对应应用，与服务部署位置无关），以及工单定时同步的
 * 开关与频率（同步使用的 ONES Cookie、AI 配置为个人配置，见 UserConfig）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GlobalConfig {

    /** 远程方式名 → 唤起链接（URL 协议，如 sunlogin://）；用 LinkedHashMap 保持配置顺序 */
    private Map<String, String> launchers = new LinkedHashMap<>();

    /** 是否按自定义间隔自动同步工单；null 视为关闭 */
    private Boolean ticketAutoSyncEnabled;

    /** 自定义自动同步间隔（分钟），最小 1 */
    private Integer ticketAutoSyncIntervalMinutes;

    public Map<String, String> getLaunchers() {
        return launchers;
    }

    public void setLaunchers(Map<String, String> launchers) {
        this.launchers = launchers;
    }

    public Boolean getTicketAutoSyncEnabled() {
        return ticketAutoSyncEnabled;
    }

    public void setTicketAutoSyncEnabled(Boolean ticketAutoSyncEnabled) {
        this.ticketAutoSyncEnabled = ticketAutoSyncEnabled;
    }

    public Integer getTicketAutoSyncIntervalMinutes() {
        return ticketAutoSyncIntervalMinutes;
    }

    public void setTicketAutoSyncIntervalMinutes(Integer ticketAutoSyncIntervalMinutes) {
        this.ticketAutoSyncIntervalMinutes = ticketAutoSyncIntervalMinutes;
    }
}
