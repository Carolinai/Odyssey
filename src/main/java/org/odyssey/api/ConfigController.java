package org.odyssey.api;

import org.odyssey.model.GlobalConfig;
import org.odyssey.store.GlobalConfigStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局配置 REST 接口：读取 / 整体保存（保存以请求体为准，全量覆盖）。
 * 仅系统级配置（远程方式映射、定时同步开关与频率）；
 * 工单助手的个人配置见 {@link UserConfigController}。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final GlobalConfigStore store;

    public ConfigController(GlobalConfigStore store) {
        this.store = store;
    }

    @GetMapping
    public GlobalConfig get() {
        return store.get();
    }

    @PutMapping
    public GlobalConfig update(@RequestBody GlobalConfig config) {
        store.save(config);
        return store.get(); // 返回归一化后的结果，前端据此刷新
    }
}
