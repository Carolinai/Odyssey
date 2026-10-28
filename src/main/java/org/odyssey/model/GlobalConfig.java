package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局配置（服务级，全站共享）：目前包含「远程方式 → 唤起链接」映射，
 * 如 向日葵 → sunlogin://。唤起走自定义 URL 协议，由访问者本机的
 * 协议注册唤起对应应用，与服务部署位置无关。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GlobalConfig {

    /** 远程方式名 → 唤起链接（URL 协议，如 sunlogin://）；用 LinkedHashMap 保持配置顺序 */
    private Map<String, String> launchers = new LinkedHashMap<>();

    public Map<String, String> getLaunchers() {
        return launchers;
    }

    public void setLaunchers(Map<String, String> launchers) {
        this.launchers = launchers;
    }
}
