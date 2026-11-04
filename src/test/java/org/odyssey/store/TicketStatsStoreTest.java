package org.odyssey.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 看板统计聚合测试：真实 SQLite + 固定夹具，覆盖卡片/趋势/分布/下钻与筛选组合。
 * 夹具时间统一 UTC+8 构造，验证按月分桶的时区正确性。
 */
class TicketStatsStoreTest {

    @TempDir
    Path tempDir;

    private TicketStatsStore stats;
    private JdbcTemplate jdbc;

    private static long at(String isoLocal) {
        return ZonedDateTime.parse(isoLocal + "+08:00").toInstant().toEpochMilli();
    }

    /** SQLite 计数列经 JDBC 可能是 Integer 或 Long，统一转 long 比较 */
    private static long num(Object o) {
        return ((Number) o).longValue();
    }

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("odyssey.db"));
        jdbc = new JdbcTemplate(ds);
        new TicketSchemaInitializer(jdbc).run(null);
        stats = new TicketStatsStore(jdbc);
        insertFixture();
    }

    /** 5 张工单：覆盖 done/in_progress/to_do、有无客户、有无解决历史、跨月边界 */
    private void insertFixture() {
        // t1: done（已解决），3 月创建 4 月解决，QMS/检验，张三
        insertTicket("t1", 101, "登录失败", "done", "已解决", "高", "u1", "张三", "正大天晴",
                at("2024-03-15T10:00:00"));
        jdbc.update("INSERT INTO ticket_product(ticket_uuid, product_name) VALUES ('t1','QMS')");
        jdbc.update("INSERT INTO ticket_module(ticket_uuid, module_name) VALUES ('t1','检验')");
        jdbc.update("INSERT INTO ticket_history(ticket_uuid, send_time, operator_uuid, field_name, old_value, new_value)"
                        + " VALUES ('t1', ?, 'u3', '状态', '待办', '已解决')", at("2024-04-02T09:00:00"));
        // 幽灵操作人：更早转入过 done 但不在 ones_user 缓存（离职账号场景），
        // 解决者下拉不能出现空名项——最后解决者仍取更晚的 u3
        jdbc.update("INSERT INTO ticket_history(ticket_uuid, send_time, operator_uuid, field_name, old_value, new_value)"
                        + " VALUES ('t1', ?, 'ghost', '状态', '待办', '已解决')", at("2024-03-25T10:00:00"));
        // 解决者姓名来自 ones_user 缓存
        jdbc.update("INSERT INTO ones_user(uuid, name) VALUES ('u1', '张三')");
        jdbc.update("INSERT INTO ones_user(uuid, name) VALUES ('u2', '李四')");
        jdbc.update("INSERT INTO ones_user(uuid, name) VALUES ('u3', '王五')");

        // t2: in_progress（分析中），3 月创建，TMS，张三
        insertTicket("t2", 102, "导出报错", "in_progress", "分析中", "中", "u1", "张三", "澳斯康",
                at("2024-03-20T14:00:00"));
        jdbc.update("INSERT INTO ticket_product(ticket_uuid, product_name) VALUES ('t2','TMS')");

        // t3: done（已发布）但无状态流转历史（解决时间缺失场景），4 月创建
        insertTicket("t3", 103, "打印格式错", "done", "已发布", "低", "u2", "李四", "正大天晴",
                at("2024-04-10T09:00:00"));
        jdbc.update("INSERT INTO ticket_product(ticket_uuid, product_name) VALUES ('t3','QMS')");

        // t4: to_do（待办），无客户
        insertTicket("t4", 104, "需求待评估", "to_do", "待办", "中", "u2", "李四", null,
                at("2024-04-20T16:00:00"));
        jdbc.update("INSERT INTO ticket_product(ticket_uuid, product_name) VALUES ('t4','QMS')");

        // t5: 2024-03-01 06:59 +08（UTC 是 2 月 29 日），验证分桶按 UTC+8 归 2024-02
        insertTicket("t5", 105, "时区边界", "done", "已解决", "低", "u1", "张三", "甘李",
                at("2024-03-01T06:59:00"));
        jdbc.update("INSERT INTO ticket_history(ticket_uuid, send_time, operator_uuid, field_name, old_value, new_value)"
                + " VALUES ('t5', ?, 'u1', '状态', '待办', '已解决')", at("2024-03-01T08:00:00"));

        // t6: 已打回待确认（ONES 进行类，但业务上单独成「待确认 / 待发布」维，不计入进行中/已解决）
        insertTicket("t6", 106, "等待确认", "in_progress", "已打回待确认", "中", "u2", "李四", "正大天晴",
                at("2024-04-15T11:00:00"));

        // t7: 仅转入过「已打回待确认」——解决者按「处理完」归属，算到最后操作人头上，但不进解决时间/解决率
        insertTicket("t7", 107, "需求打回", "in_progress", "已打回待确认", "低", "u1", "张三", "澳斯康",
                at("2024-04-16T10:00:00"));
        jdbc.update("INSERT INTO ticket_history(ticket_uuid, send_time, operator_uuid, field_name, old_value, new_value)"
                + " VALUES ('t7', ?, 'u1', '状态', '待办', '已打回待确认')", at("2024-04-17T10:00:00"));

        // 关注人：t1 由李四（u2）关注、t5/t7 由张三（u1）关注
        jdbc.update("INSERT INTO ticket_watcher(ticket_uuid, watcher_uuid) VALUES ('t1','u2')");
        jdbc.update("INSERT INTO ticket_watcher(ticket_uuid, watcher_uuid) VALUES ('t5','u1')");
        jdbc.update("INSERT INTO ticket_watcher(ticket_uuid, watcher_uuid) VALUES ('t7','u1')");
    }

    private void insertTicket(String uuid, int number, String name, String category, String status,
                              String priority, String assignUuid, String assignName,
                              String customer, long createTime) {
        jdbc.update("INSERT INTO ticket(uuid, number, name, description_text, status_category,"
                        + " status_name, priority, assign_uuid, assign_name, customer_uuid, customer_name,"
                        + " create_time, server_update_stamp, synced_at)"
                        + " VALUES (?, ?, ?, '描述', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                uuid, number, name, category, status, priority, assignUuid, assignName,
                customer == null ? null : customer + "-uuid", customer, createTime, createTime, createTime);
    }

    private static TicketStatsStore.Filter filter(String product, String customer,
                                                  String statusCategory, String statusName, String assignee) {
        return filter(product, customer, statusCategory, statusName, assignee, null);
    }

    private static TicketStatsStore.Filter filter(String product, String customer,
                                                  String statusCategory, String statusName, String assignee,
                                                  String resolver) {
        return filter(product, customer, statusCategory, statusName, assignee, resolver, null);
    }

    private static TicketStatsStore.Filter filter(String product, String customer,
                                                  String statusCategory, String statusName, String assignee,
                                                  String resolver, String watcher) {
        return TicketStatsStore.Filter.of(null, null, product, customer,
                statusCategory, statusName, assignee, resolver, watcher);
    }

    // ---------- 统计卡片 ----------

    @Test
    void cardsAllAndResolveRate() {
        Map<String, Object> c = stats.cards(filter(null, null, null, null, null));
        assertEquals(7L, num(c.get("total")));
        assertEquals(1L, num(c.get("to_do")));
        assertEquals(1L, num(c.get("in_progress")));    // t2 分析中
        assertEquals(2L, num(c.get("processed")));      // t6/t7 已打回待确认 → 单独维
        assertEquals(3L, num(c.get("done")));
        assertEquals(42.9, c.get("resolve_rate"));      // 3/7，待确认/待发布不计入
    }

    @Test
    void cardsWithFilter() {
        // 按产品线筛 QMS：t1/t3/t4
        Map<String, Object> c = stats.cards(filter("QMS", null, null, null, null));
        assertEquals(3L, num(c.get("total")));
        assertEquals(1L, num(c.get("to_do")));
        assertEquals(2L, num(c.get("done")));

        // 按状态分类筛 done
        assertEquals(3L, num(stats.cards(filter(null, null, "done", null, null)).get("total")));
        // 按负责人筛（uuid）：t1/t2/t5/t7
        assertEquals(4L, num(stats.cards(filter(null, null, null, null, "u1")).get("total")));
        // 空范围
        assertEquals(0L, num(stats.cards(filter("不存在", null, null, null, null)).get("total")));
        assertNull(stats.cards(filter("不存在", null, null, null, null)).get("resolve_rate"));
    }

    // ---------- 月度趋势 ----------

    @Test
    void monthlyTrendBucketsUtc8() {
        List<Map<String, Object>> trend = stats.monthlyTrend(filter(null, null, null, null, null));
        // t5 创建时间 UTC 是 2024-02-29 22:59，但本地（UTC+8）是 2024-03-01 06:59——
        // 必须归入 2024-03；若按原始 UTC 分桶会错归 2024-02，此处恰好能区分两种实现
        assertEquals(2, trend.size());
        assertEquals("2024-03", trend.get(0).get("month"));
        assertEquals(3L, num(trend.get(0).get("created")));  // t1/t2/t5
        assertEquals(1L, num(trend.get(0).get("resolved"))); // t5（解决历史也在 3 月 1 日 +08）
        assertEquals("2024-04", trend.get(1).get("month"));
        assertEquals(4L, num(trend.get(1).get("created")));  // t3/t4/t6/t7
        assertEquals(1L, num(trend.get(1).get("resolved"))); // 仅 t1（t3 无解决历史、t7 未进已解决类，不计）
    }

    // ---------- 分布 ----------

    @Test
    void byProductAndModule() {
        List<Map<String, Object>> products = stats.byProduct(filter(null, null, null, null, null), 10);
        assertEquals(2, products.size());
        assertEquals("QMS", products.get(0).get("name"));
        assertEquals(3L, num(products.get(0).get("n")));
        // 模块只有 t1 有
        List<Map<String, Object>> modules = stats.byModule(filter(null, null, null, null, null), 10);
        assertEquals(1, modules.size());
        assertEquals("检验", modules.get(0).get("name"));
    }

    @Test
    void byCustomerTopN() {
        List<Map<String, Object>> top1 = stats.byCustomer(filter(null, null, null, null, null), 1);
        assertEquals(1, top1.size());
        assertEquals("正大天晴", top1.get(0).get("name"));
        assertEquals(3L, num(top1.get(0).get("n"))); // t1/t3/t6（t6 虽是待确认维，未选状态时不默认筛状态）
        // 全量 3 个客户（t4 无客户不计入）
        assertEquals(3, stats.byCustomer(filter(null, null, null, null, null), 10).size());
    }

    @Test
    void byAssigneeDoingVsDone() {
        List<Map<String, Object>> rows = stats.byAssignee(filter(null, null, null, null, null), 20);
        assertEquals(2, rows.size());
        Map<String, Object> zhang = rows.stream()
                .filter(r -> "张三".equals(r.get("name"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("u1", zhang.get("uuid")); // 下钻按 uuid 反查
        assertEquals(0L, num(zhang.get("todo")));
        assertEquals(1L, num(zhang.get("doing")));
        assertEquals(1L, num(zhang.get("processed"))); // t7 已打回待确认
        assertEquals(2L, num(zhang.get("done"))); // t1/t5 已解决
        Map<String, Object> li = rows.stream()
                .filter(r -> "李四".equals(r.get("name"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals(1L, num(li.get("todo")));      // t4
        assertEquals(0L, num(li.get("doing")));     // t3 是 done，无 in_progress
        assertEquals(1L, num(li.get("processed"))); // t6 已打回待确认 → 单独维，不算进行中
        assertEquals(1L, num(li.get("done")));
    }

    /** 解决者 = 最后一次转入「已解决类 或 待确认/待发布」状态的操作人（与负责人是两个概念） */
    @Test
    void resolverFilterAndDrill() {
        // t1 由王五（u3）解决、t5 由张三（u1）解决、t7 由张三转「已打回待确认」也归属张三；t3 无解决历史
        assertEquals(1L, num(stats.cards(filter(null, null, null, null, null, "u3")).get("total")));
        assertEquals(2L, num(stats.cards(filter(null, null, null, null, null, "u1")).get("total")));
        assertEquals(3L, num(stats.cards(filter(null, null, "done", null, null, null)).get("total")));

        // 下钻：解决者维度 + 明细行带解决者姓名
        Map<String, Object> d = stats.drill(filter(null, null, null, null, null, null),
                "resolver", "u3", 1, 20);
        assertEquals(1L, num(d.get("total")));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) d.get("rows")).get(0);
        assertEquals(101, row.get("number"));
        assertEquals("王五", row.get("resolver_name"));

        // 仅转入过「已打回待确认」的工单也归属解决者（t7），明细带姓名
        Map<String, Object> d7 = stats.drill(filter(null, null, null, null, null, null),
                "resolver", "u1", 1, 20);
        assertEquals(2L, num(d7.get("total"))); // t5 + t7
        Map<?, ?> row7 = (Map<?, ?>) ((List<?>) d7.get("rows")).get(0); // 按创建时间倒序，t7 在前
        assertEquals(107, row7.get("number"));
        assertEquals("张三", row7.get("resolver_name"));
    }

    /** 关注者筛选：按 ticket_watcher 关联反查，选项来自关联表经 ones_user 解析 */
    @Test
    void watcherFilterAndOptions() {
        // 李四（u2）关注 t1 → 1 条；张三（u1）关注 t5/t7 → 2 条
        assertEquals(1L, num(stats.cards(filter(null, null, null, null, null, null, "u2")).get("total")));
        assertEquals(2L, num(stats.cards(filter(null, null, null, null, null, null, "u1")).get("total")));
        // 与其他条件叠加：产品线 QMS + 关注 u1（t5 是甘李无产品、t7 无产品）→ 0 条
        assertEquals(0L, num(stats.cards(filter("QMS", null, null, null, null, null, "u1")).get("total")));
        // 选项：张三、李四（无缓存姓名的 uuid 不出现）
        Map<String, Object> o = stats.filterOptions();
        assertEquals(2, ((List<?>) o.get("watchers")).size());
    }

    @Test
    void byPriorityFixedOrder() {
        List<Map<String, Object>> rows = stats.byPriority(filter(null, null, null, null, null));
        assertEquals(3, rows.size());
        assertEquals("高", rows.get(0).get("name"));
        assertEquals("中", rows.get(1).get("name"));
        assertEquals("低", rows.get(2).get("name"));
    }

    // ---------- 筛选选项 ----------

    @Test
    void filterOptionsCoverDistinctValues() {
        Map<String, Object> o = stats.filterOptions();
        assertEquals(2, ((List<?>) o.get("products")).size()); // QMS / TMS
        assertEquals(3, ((List<?>) o.get("customers")).size());
        assertEquals(2, ((List<?>) o.get("assignees")).size());
        // 解决者：王五（t1 最后解决者）、张三（t5），ghost 无姓名缓存被过滤
        assertEquals(2, ((List<?>) o.get("resolvers")).size());
        // 状态选项按业务分类+名称去重：done/已解决、done/已发布、in_progress/分析中、
        // processed/已打回待确认（业务维）、to_do/待办
        assertEquals(5, ((List<?>) o.get("statuses")).size());
    }

    // ---------- 下钻 ----------

    @Test
    void drillByProductWithPaging() {
        Map<String, Object> p1 = stats.drill(filter(null, null, null, null, null), "product", "QMS", 1, 2);
        assertEquals(3L, num(p1.get("total")));
        assertEquals(2, ((List<?>) p1.get("rows")).size());
        Map<String, Object> p2 = stats.drill(filter(null, null, null, null, null), "product", "QMS", 2, 2);
        assertEquals(1, ((List<?>) p2.get("rows")).size());
        // 行内带产品线聚合
        Map<?, ?> row = (Map<?, ?>) ((List<?>) p2.get("rows")).get(0);
        assertEquals("QMS", row.get("products"));
    }

    @Test
    void drillByResolvedMonthUsesHistoryOnly() {
        // 2024-04 只解决 1 张（t1），t3 无历史不计
        Map<String, Object> m = stats.drill(filter(null, null, null, null, null),
                "month_resolved", "2024-04", 1, 20);
        assertEquals(1L, num(m.get("total")));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) m.get("rows")).get(0);
        assertEquals(101, row.get("number"));
    }

    @Test
    void drillCombinedWithGlobalFilter() {
        // 全局筛 done + 下钻产品线 QMS：t1/t3
        Map<String, Object> m = stats.drill(filter("QMS", null, "done", null, null),
                "product", "QMS", 1, 20);
        assertEquals(2L, num(m.get("total")));
        // 全局筛 done + 下钻月份新增 2024-04：只有 t3
        Map<String, Object> mc = stats.drill(filter(null, null, "done", null, null),
                "month_created", "2024-04", 1, 20);
        assertEquals(1L, num(mc.get("total")));
    }

    @Test
    void drillRejectsBadDimAndBlankValue() {
        assertThrows(IllegalArgumentException.class,
                () -> stats.drill(filter(null, null, null, null, null), "unknown", "x", 1, 20));
        assertThrows(IllegalArgumentException.class,
                () -> stats.drill(filter(null, null, null, null, null), "customer", " ", 1, 20));
        assertThrows(IllegalArgumentException.class,
                () -> stats.drill(filter(null, null, null, null, null), "month_created", "2024-13", 1, 20));
    }
}
