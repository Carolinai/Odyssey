package org.odyssey.api;

import org.odyssey.model.UserConfig;
import org.odyssey.service.AuditService;
import org.odyssey.service.AuthService;
import org.odyssey.service.TicketSyncService;
import org.odyssey.store.UserConfigStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工单同步接口：手动触发（记审计日志）与状态/日志查询（同步管理页轮询用）。
 */
@RestController
@RequestMapping("/api/tickets/sync")
public class TicketSyncController {

    private final TicketSyncService syncService;
    private final AuthService authService;
    private final AuditService auditService;
    private final UserConfigStore userConfigStore;

    public TicketSyncController(TicketSyncService syncService, AuthService authService,
                                AuditService auditService, UserConfigStore userConfigStore) {
        this.syncService = syncService;
        this.authService = authService;
        this.auditService = auditService;
        this.userConfigStore = userConfigStore;
    }

    /**
     * 手动触发同步（无水位时自动执行全量，否则增量）；已有同步在跑时拒绝。
     * ONES Cookie 为个人配置：未配置时拦截并给出引导文案（工单 13）。
     */
    @PostMapping("/run")
    public Map<String, Object> run(javax.servlet.http.HttpServletRequest request) {
        String user = authService.currentUser(request);
        Map<String, Object> result = new LinkedHashMap<>();
        UserConfig cfg = userConfigStore.get(user);
        if (cfg.getOnesCookie() == null || cfg.getOnesCookie().trim().isEmpty()) {
            result.put("started", false);
            result.put("need_cookie", true);
            result.put("message", "您还没有配置 ONES Cookie：请到「个人中心 → 工单助手配置」填写并保存后再同步");
            return result;
        }
        boolean full = isBlank(syncService.statusSnapshot().get("watermark"));
        boolean started = syncService.startSync("manual", user);
        result.put("started", started);
        if (started) {
            // 审计：谁、何时、触发何种同步（完成情况见同步日志，不重复记）
            auditService.record(user,
                    authService.currentUserName(request), "工单助手", "同步",
                    full ? "工单数据（全量）" : "工单数据（增量）", "同步管理", "手动触发同步");
        } else {
            result.put("message", "已有同步在执行中，请稍后再试");
        }
        return result;
    }

    /** 同步状态：进行中进度 / 最近成功 / 最近失败原因 / 最近日志（20 条） */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return syncService.statusSnapshot();
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty();
    }
}
