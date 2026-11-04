package org.odyssey.api;

import org.odyssey.model.UserConfig;
import org.odyssey.service.AuditService;
import org.odyssey.service.AuthService;
import org.odyssey.service.InsightService;
import org.odyssey.service.TaskExecutor;
import org.odyssey.store.InsightStore;
import org.odyssey.store.TaskStore;
import org.odyssey.store.UserConfigStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解决经验接口（spec §5.2③）：
 * - GET  /api/tickets/insights          分页列表（客户/产品线/关键词筛选）
 * - GET  /api/tickets/insights/options  筛选下拉（客户/产品线）
 * - GET  /api/tickets/insights/pending  待提炼数量
 * - GET  /api/tickets/insights/{id}     经验详情（Markdown 正文）
 * - POST /api/tickets/insights/extract  手动触发提炼任务（记审计日志）
 */
@RestController
@RequestMapping("/api/tickets/insights")
public class InsightController {

    private final InsightStore store;
    private final InsightService insightService;
    private final TaskExecutor taskExecutor;
    private final TaskStore taskStore;
    private final AuthService authService;
    private final AuditService auditService;
    private final UserConfigStore userConfigStore;

    public InsightController(InsightStore store, InsightService insightService,
                             TaskExecutor taskExecutor, TaskStore taskStore,
                             AuthService authService,
                             AuditService auditService, UserConfigStore userConfigStore) {
        this.store = store;
        this.insightService = insightService;
        this.taskExecutor = taskExecutor;
        this.taskStore = taskStore;
        this.authService = authService;
        this.auditService = auditService;
        this.userConfigStore = userConfigStore;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String customer,
                                    @RequestParam(required = false) String product,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(size, 100));
        return store.list(customer, product, q, p, s);
    }

    @GetMapping("/options")
    public Map<String, Object> options() {
        return store.options();
    }

    /** 待提炼数量（已解决且尚无经验），供经验库/同步管理页展示全量进度 */
    @GetMapping("/pending")
    public Map<String, Object> pending() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pending", store.countPending());
        return m;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("insight", store.get(id));
        return m;
    }

    /**
     * 手动触发提炼（每次一批，跑完可再点继续；AI 配置缺失提前拦截）。
     * 同步后自动增量在 TicketSyncService 完成回调里触发，不经过这里。
     */
    @PostMapping("/extract")
    public Map<String, Object> extract(HttpServletRequest request) {
        String user = authService.currentUser(request);
        Map<String, Object> result = new LinkedHashMap<>();

        UserConfig cfg = userConfigStore.get(user);
        if (isBlank(cfg.getAiBaseUrl()) || isBlank(cfg.getAiApiKey()) || isBlank(cfg.getAiModel())) {
            result.put("started", false);
            result.put("need_ai_config", true);
            result.put("message", "您还没有配置 AI：请到「个人中心 → 工单助手配置」填写 AI Base URL、API Key 与模型名");
            return result;
        }

        long taskId = taskExecutor.submit("insight_batch", null, user,
                ctx -> insightService.run(ctx, user, null, null));

        auditService.record(user, authService.currentUserName(request), "工单助手", "提炼",
                "解决经验", "经验库", "手动触发经验提炼");

        result.put("started", true);
        result.put("task_id", taskId);
        return result;
    }

    /**
     * 终止经验提炼：正在跑的任务协作式停止（当前这张跑完、下一张不再开始，结果正常收尾）；
     * 还在排队的任务直接标记失败（不执行）。没有进行中的提炼时返回提示。
     */
    @PostMapping("/stop")
    public Map<String, Object> stop(HttpServletRequest request) {
        String user = authService.currentUser(request);
        List<Map<String, Object>> unfinished = taskStore.unfinishedByType("insight_batch");
        int cancelled = 0;
        for (Map<String, Object> task : unfinished) {
            long id = ((Number) task.get("id")).longValue();
            if ("running".equals(task.get("status"))) {
                insightService.requestStop(id); // 协作式：本轮跑完即停，任务正常 done
            } else {
                taskStore.markFailed(id, "已手动终止"); // 排队中：直接取消（TaskExecutor 跳过执行）
                cancelled++;
            }
        }
        if (!unfinished.isEmpty()) {
            auditService.record(user, authService.currentUserName(request), "工单助手", "终止",
                    "解决经验", "经验库", "终止经验提炼（进行中 "
                            + (unfinished.size() - cancelled) + " 个、排队中 " + cancelled + " 个）");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stopped", !unfinished.isEmpty());
        result.put("cancelled", cancelled);
        if (unfinished.isEmpty()) {
            result.put("message", "当前没有正在进行的经验提炼");
        }
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
