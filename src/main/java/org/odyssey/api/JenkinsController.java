package org.odyssey.api;

import org.odyssey.service.JenkinsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jenkins 视图接口：主页「Jenkins 视图」区块用。
 */
@RestController
@RequestMapping("/api/jenkins")
public class JenkinsController {

    private static final Logger log = LoggerFactory.getLogger(JenkinsController.class);

    private final JenkinsService service;

    public JenkinsController(JenkinsService service) {
        this.service = service;
    }

    @GetMapping("/views")
    public ResponseEntity<?> views() {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("views", service.fetchViews());
            // 前端拼视图详情页链接用（{base}/view/{视图名}/）
            result.put("base_url", service.getBaseUrl());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            log.warn("获取 Jenkins 视图失败：{}", e.getMessage());
            return body(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        Map<String, String> map = new HashMap<>();
        map.put("message", message);
        return ResponseEntity.status(status).body(map);
    }
}
