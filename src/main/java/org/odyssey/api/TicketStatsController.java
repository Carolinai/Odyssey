package org.odyssey.api;

import org.odyssey.store.TicketStatsStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工单看板统计接口（spec §6 看板页）：
 * - GET /api/tickets/stats          统计卡片 + 全部图表数据（筛选联动）
 * - GET /api/tickets/stats/options  筛选区下拉选项（产品线/客户/负责人/状态）
 * - GET /api/tickets/stats/drill    图表下钻工单分页列表
 *
 * 均为只读查询，不记审计日志。
 */
@RestController
@RequestMapping("/api/tickets/stats")
public class TicketStatsController {

    private final TicketStatsStore store;

    public TicketStatsController(TicketStatsStore store) {
        this.store = store;
    }

    /** 看板全量数据：一次请求带回卡片与所有图表，筛选参数联动生效 */
    @GetMapping
    public Map<String, Object> overview(HttpServletRequest request,
                                        @RequestParam(required = false) Long from,
                                        @RequestParam(required = false) Long to,
                                        @RequestParam(required = false) String product,
                                        @RequestParam(required = false) String customer,
                                        @RequestParam(required = false) String status_category,
                                        @RequestParam(required = false) String status_name,
                                        @RequestParam(required = false) String assignee,
                                        @RequestParam(required = false) String resolver,
                                        @RequestParam(required = false) String watcher,
                                        @RequestParam(defaultValue = "10") int customer_top) {
        TicketStatsStore.Filter f = TicketStatsStore.Filter.of(
                from, to, product, customer, status_category, status_name, assignee, resolver, watcher);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cards", store.cards(f));
        m.put("trend", store.monthlyTrend(f));
        m.put("products", store.byProduct(f, 15));
        m.put("modules", store.byModule(f, 15));
        m.put("customers", store.byCustomer(f, clamp(customer_top, 1, 50, 10)));
        m.put("assignees", store.byAssignee(f, 20));
        m.put("priorities", store.byPriority(f));
        return m;
    }

    /** 筛选区下拉选项（不受筛选影响，页面加载时取一次） */
    @GetMapping("/options")
    public Map<String, Object> options(HttpServletRequest request) {
        return store.filterOptions();
    }

    /** 图表下钻：按维度 + 取值反查工单分页列表 */
    @GetMapping("/drill")
    public Map<String, Object> drill(HttpServletRequest request,
                                     @RequestParam(required = false) Long from,
                                     @RequestParam(required = false) Long to,
                                     @RequestParam(required = false) String product,
                                     @RequestParam(required = false) String customer,
                                     @RequestParam(required = false) String status_category,
                                     @RequestParam(required = false) String status_name,
                                     @RequestParam(required = false) String assignee,
                                     @RequestParam(required = false) String resolver,
                                     @RequestParam(required = false) String watcher,
                                     @RequestParam String dim,
                                     @RequestParam String value,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        TicketStatsStore.Filter f = TicketStatsStore.Filter.of(
                from, to, product, customer, status_category, status_name, assignee, resolver, watcher);
        return store.drill(f, dim, value, clamp(page, 1, Integer.MAX_VALUE, 1),
                clamp(size, 1, 100, 20));
    }

    private static int clamp(int v, int min, int max, int def) {
        if (v <= 0) return def; // 缺省/非法时回默认
        return Math.max(min, Math.min(max, v));
    }
}
