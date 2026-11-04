package org.odyssey.api;

import org.odyssey.model.UserConfig;
import org.odyssey.service.AiGatewayClient;
import org.odyssey.service.AuthService;
import org.odyssey.store.UserConfigStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 个人配置 REST 接口（工单助手配置，按当前登录用户隔离）：
 * 读取 / 整体保存（保存以请求体为准，全量覆盖），以及 AI 配置连通性测试。
 */
@RestController
@RequestMapping("/api/user-config")
public class UserConfigController {

    private final UserConfigStore store;
    private final AiGatewayClient aiGatewayClient;
    private final AuthService authService;

    public UserConfigController(UserConfigStore store, AiGatewayClient aiGatewayClient,
                                AuthService authService) {
        this.store = store;
        this.aiGatewayClient = aiGatewayClient;
        this.authService = authService;
    }

    /** 当前用户的个人配置 */
    @GetMapping
    public UserConfig get(javax.servlet.http.HttpServletRequest request) {
        return store.get(authService.currentUser(request));
    }

    /** 保存当前用户的个人配置，返回归一化后的结果供前端刷新 */
    @PutMapping
    public UserConfig update(javax.servlet.http.HttpServletRequest request,
                             @RequestBody UserConfig config) {
        store.save(authService.currentUser(request), config);
        return store.get(authService.currentUser(request));
    }

    /**
     * AI 配置连通性测试：用页面提交的值发一次最小请求（未填的项回落到当前用户已保存的
     * 个人配置），便于保存前验证。请求体各字段均可缺省。
     */
    @PostMapping("/ai-test")
    public Map<String, Object> testAi(javax.servlet.http.HttpServletRequest request,
                                      @RequestBody(required = false) Map<String, String> body) {
        UserConfig saved = store.get(authService.currentUser(request));
        String baseUrl = firstNonBlank(
                body == null ? null : body.get("ai_base_url"), saved.getAiBaseUrl());
        String apiKey = firstNonBlank(
                body == null ? null : body.get("ai_api_key"), saved.getAiApiKey());
        String model = firstNonBlank(
                body == null ? null : body.get("ai_model"), saved.getAiModel());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("ok", false);
        if (baseUrl == null || apiKey == null || model == null) {
            result.put("message", "配置不完整：需要 AI Base URL、API Key 和模型名（页面留空的项按已保存配置计）");
            return result;
        }
        return aiGatewayClient.testConnectivity(baseUrl, apiKey, model);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }
}
