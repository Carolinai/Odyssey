package org.odyssey.api;

import org.odyssey.model.AuditLog;
import org.odyssey.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计日志接口：审计日志页（全量、按对象名称聚合由前端分组）与
 * 客户详情页（targetName 过滤）共用。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuditLog> list(@RequestParam(value = "target_name", required = false) String targetName) {
        return service.list(targetName);
    }
}
