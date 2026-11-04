package org.odyssey.api;

import org.odyssey.model.UserConfig;
import org.odyssey.service.AuditService;
import org.odyssey.service.AuthService;
import org.odyssey.service.TaskExecutor;
import org.odyssey.service.TicketReportService;
import org.odyssey.store.TicketReportStore;
import org.odyssey.store.TicketStatsStore;
import org.odyssey.store.UserConfigStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 工单分析报告接口（spec §5.2①）：
 * - POST /api/tickets/report      触发生成（后台任务，记审计日志）
 * - GET  /api/tickets/report      最近报告列表（报告页轮询任务进度用 /api/tasks/{id}）
 * - GET  /api/tickets/report/{id} 报告详情（Markdown 正文）
 */
@RestController
@RequestMapping("/api/tickets/report")
public class TicketReportController {

    private final TicketReportService reportService;
    private final TicketReportStore reportStore;
    private final TaskExecutor taskExecutor;
    private final AuthService authService;
    private final AuditService auditService;
    private final UserConfigStore userConfigStore;

    public TicketReportController(TicketReportService reportService, TicketReportStore reportStore,
                                  TaskExecutor taskExecutor, AuthService authService,
                                  AuditService auditService, UserConfigStore userConfigStore) {
        this.reportService = reportService;
        this.reportStore = reportStore;
        this.taskExecutor = taskExecutor;
        this.authService = authService;
        this.auditService = auditService;
        this.userConfigStore = userConfigStore;
    }

    /**
     * 触发生成报告。from/to 均未传时默认近六个月（与需求一致）；
     * AI 配置未填时直接拦截并给引导文案（任务跑到 AI 调用才会失败，提前拦体验更好）。
     */
    @PostMapping
    public Map<String, Object> create(javax.servlet.http.HttpServletRequest request,
                                      @RequestParam(required = false) Long from,
                                      @RequestParam(required = false) Long to,
                                      @RequestParam(required = false) String product,
                                      @RequestParam(required = false) String customer,
                                      @RequestParam(required = false) String status_name) {
        String user = authService.currentUser(request);
        Map<String, Object> result = new LinkedHashMap<>();

        UserConfig cfg = userConfigStore.get(user);
        if (isBlank(cfg.getAiBaseUrl()) || isBlank(cfg.getAiApiKey()) || isBlank(cfg.getAiModel())) {
            result.put("started", false);
            result.put("need_ai_config", true);
            result.put("message", "您还没有配置 AI：请到「个人中心 → 工单助手配置」填写 AI Base URL、API Key 与模型名");
            return result;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (from == null && to == null) {
            from = System.currentTimeMillis() - 183L * 24 * 3600 * 1000; // 近六个月
        }
        params.put("from", from);
        params.put("to", to);
        params.put("product", product);
        params.put("customer", customer);
        params.put("status_name", status_name);

        TicketStatsStore.Filter filter = TicketStatsStore.Filter.of(
                from, to, product, customer, null, status_name, null, null, null);

        long taskId = taskExecutor.submit("ticket_report", params, user,
                ctx -> reportService.run(ctx, user, filter, params));

        // 审计：谁、何时、以什么范围触发（生成结果见报告列表，不重复记）
        auditService.record(user, authService.currentUserName(request), "工单助手", "生成",
                "AI 分析报告", "工单助手",
                "触发 AI 分析报告（" + TicketReportService.rangeText(params)
                        + (TicketReportService.conditionsText(params).isEmpty() ? "" : "，"
                        + TicketReportService.conditionsText(params)) + "）");

        result.put("started", true);
        result.put("task_id", taskId);
        return result;
    }

    /** 最近报告列表（新在前，不含正文） */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reports", reportStore.list(50));
        return m;
    }

    /** 报告详情（含 Markdown 正文；不存在返回 404 语义的 null data） */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("report", reportStore.get(id));
        return m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
