package org.odyssey.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 工单看板聚合查询（spec §6 看板指标，纯 SQL 规则统计、无 AI 参与）。
 *
 * 约定：
 * - 时间戳统一 Unix 毫秒；按月分桶统一 UTC+8（date(x/1000 + 28800, 'unixepoch')）
 * - 「解决时间」= 流转历史中最后一次 new_value 进入 done 类状态名的时间；无该历史的工单
 *   （历史缺失，约 12%）不进解决趋势线，但计入解决率分子分母
 * - done 类状态名不硬编码，从 ticket 表动态取（status_category='done' 的 distinct status_name）
 * - 筛选条件均为工单创建时间范围 / 产品线 / 客户 / 状态 / 负责人，作用于全部图表
 */
@Component
public class TicketStatsStore {

    private final JdbcTemplate jdbc;

    public TicketStatsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 业务状态维度（与 ONES 原始分类的差异，2026-11-03 与需求方确认）：
     * - 「已打回待确认」「已处理未发布」在 ONES 里属进行类，但实际是处理完待闭环，看板单独成维 processed（待确认 / 待发布）
     * - 「已废弃」ONES 归已解决类，维持计入解决率（已确认）
     * 其余状态沿用 ONES 的 status_category。注意：同步入库的 status_category 不改写，仅在统计层映射。
     */
    private static final String PROCESSED_STATUS_SQL = "('已打回待确认','已处理未发布')";

    /** 业务状态分类表达式（t 为工单别名）：processed = 待确认 / 待发布，其余沿用 ONES 分类 */
    private static String bizCategoryExpr() {
        return "(CASE WHEN t.status_name IN " + PROCESSED_STATUS_SQL
                + " THEN 'processed' ELSE t.status_category END)";
    }

    // ---------- 筛选条件 ----------

    /** 看板筛选条件（均为可选；blank 视为不过滤） */
    public static class Filter {
        public Long from;             // 创建时间下界（毫秒，含）
        public Long to;               // 创建时间上界（毫秒，含）
        public String product;        // 产品线名
        public String customer;       // 客户名
        public String statusCategory; // to_do / in_progress / done
        public String statusName;     // 状态名（精确）
        public String assignee;       // 负责人 uuid
        public String resolver;       // 解决者 uuid（最后一次转入已解决类或待确认/待发布状态的操作人）
        public String watcher;        // 关注人 uuid

        public static Filter of(Long from, Long to, String product, String customer,
                                String statusCategory, String statusName, String assignee) {
            return of(from, to, product, customer, statusCategory, statusName, assignee, null, null);
        }

        public static Filter of(Long from, Long to, String product, String customer,
                                String statusCategory, String statusName, String assignee,
                                String resolver) {
            return of(from, to, product, customer, statusCategory, statusName, assignee, resolver, null);
        }

        public static Filter of(Long from, Long to, String product, String customer,
                                String statusCategory, String statusName, String assignee,
                                String resolver, String watcher) {
            Filter f = new Filter();
            f.from = from;
            f.to = to;
            f.product = trimToNull(product);
            f.customer = trimToNull(customer);
            f.statusCategory = trimToNull(statusCategory);
            f.statusName = trimToNull(statusName);
            f.assignee = trimToNull(assignee);
            f.resolver = trimToNull(resolver);
            f.watcher = trimToNull(watcher);
            return f;
        }

        private static String trimToNull(String s) {
            if (s == null) return null;
            s = s.trim();
            return s.isEmpty() ? null : s;
        }

        /** 把筛选条件写进 where（以 t 为工单表别名） */
        void apply(TicketStatsStore store, Where w) {
            if (from != null) w.add("t.create_time >= ?", from);
            if (to != null) w.add("t.create_time <= ?", to);
            if (product != null) w.add("EXISTS (SELECT 1 FROM ticket_product tp"
                    + " WHERE tp.ticket_uuid = t.uuid AND tp.product_name = ?)", product);
            if (customer != null) w.add("t.customer_name = ?", customer);
            if (statusCategory != null) w.add("t.status_category = ?", statusCategory);
            if (statusName != null) w.add("t.status_name = ?", statusName);
            if (assignee != null) w.add("t.assign_uuid = ?", assignee);
            if (watcher != null) w.add("EXISTS (SELECT 1 FROM ticket_watcher tw"
                    + " WHERE tw.ticket_uuid = t.uuid AND tw.watcher_uuid = ?)", watcher);
            if (resolver != null) {
                // 解决者 = 最后一次转入「已解决类 或 待确认/待发布」状态的操作人
                //（SQLite MAX 聚合下裸列取自最大值所在行）。
                // 注意占位符顺序：状态名 IN 在 ruuid = ? 之前，参数必须先加状态名再加 resolver
                List<Object> n = new ArrayList<>();
                String rn = store.resolverNamesPlaceholder(n);
                w.args.addAll(n);
                w.add("t.uuid IN (SELECT tid FROM (SELECT ticket_uuid tid,"
                        + " MAX(send_time) rat, operator_uuid ruuid FROM ticket_history h"
                        + " WHERE h.field_name = '状态' AND h.new_value IN (" + rn + ")"
                        + " GROUP BY h.ticket_uuid) WHERE ruuid = ?)", resolver);
            }
        }
    }

    /** WHERE 片段 + 按序参数的累积器（避免手拼字符串与参数错位） */
    static class Where {
        final StringBuilder sql = new StringBuilder();
        final List<Object> args = new ArrayList<>();

        Where add(String cond, Object... a) {
            sql.append(" AND ").append(cond);
            for (Object o : a) args.add(o);
            return this;
        }
    }

    /** done 类状态名（动态取自 ticket 表，不硬编码）；names 为空时返回恒假占位 '' */
    private String doneNamesPlaceholder(List<Object> args) {
        List<String> names = jdbc.queryForList(
                "SELECT DISTINCT status_name FROM ticket WHERE status_category = 'done'", String.class);
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(',');
            sb.append('?');
            args.add(n);
        }
        return sb.length() == 0 ? "NULL" : sb.toString();
    }

    /**
     * 解决者归属的状态名占位 = 已解决类 + 待确认/待发布（2026-11-03 用户明确：
     * 处理完工单即使还没发布/确认，也要归属到处理人名下）。参数按占位顺序追加进 args。
     */
    private String resolverNamesPlaceholder(List<Object> args) {
        String dn = doneNamesPlaceholder(args);
        args.add("已打回待确认");
        args.add("已处理未发布");
        return dn.equals("NULL") ? "?,?" : dn + ",?,?";
    }

    /** 工单「解决时间」子查询表达式：最后一次流转历史进入 done 类状态的时间（毫秒，可空） */
    private String resolvedAtExpr(String doneNames) {
        return "(SELECT MAX(h.send_time) FROM ticket_history h"
                + " WHERE h.ticket_uuid = t.uuid AND h.field_name = '状态'"
                + " AND h.new_value IN (" + doneNames + "))";
    }

    // ---------- 统计卡片 ----------

    /** 总数 / 待办 / 进行中 / 待确认·待发布 / 已解决 / 解决率（解决率 = done / 总数，已废弃计入 done） */
    public Map<String, Object> cards(Filter f) {
        Where w = new Where();
        f.apply(this, w);
        String cat = bizCategoryExpr();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) total,"
                        + " SUM(CASE WHEN " + cat + "='to_do' THEN 1 ELSE 0 END) to_do,"
                        + " SUM(CASE WHEN " + cat + "='in_progress' THEN 1 ELSE 0 END) in_progress,"
                        + " SUM(CASE WHEN " + cat + "='processed' THEN 1 ELSE 0 END) processed,"
                        + " SUM(CASE WHEN " + cat + "='done' THEN 1 ELSE 0 END) done"
                        + " FROM ticket t WHERE 1=1" + w.sql, w.args.toArray());
        long total = asLong(row.get("total"));
        long done = asLong(row.get("done"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("to_do", asLong(row.get("to_do")));
        m.put("in_progress", asLong(row.get("in_progress")));
        m.put("processed", asLong(row.get("processed")));
        m.put("done", done);
        m.put("resolve_rate", total == 0 ? null : Math.round(done * 1000.0 / total) / 10.0);
        return m;
    }

    // ---------- 趋势 ----------

    /** 按月新增 vs 解决趋势：[{month, created, resolved}]，两序列月份取并集、无数据月补零 */
    public List<Map<String, Object>> monthlyTrend(Filter f) {
        Where w = new Where();
        f.apply(this, w);

        // 新增：按创建时间（UTC+8）分桶
        List<Map<String, Object>> created = jdbc.queryForList(
                "SELECT substr(date(t.create_time/1000 + 28800, 'unixepoch'),1,7) m, COUNT(*) n"
                        + " FROM ticket t WHERE 1=1" + w.sql + " GROUP BY m", w.args.toArray());

        // 解决：按最后一次进入 done 状态的历史时间分桶（无该历史的不计）
        List<Object> rArgs = new ArrayList<>(w.args);
        String doneNames = doneNamesPlaceholder(rArgs);
        List<Map<String, Object>> resolved = jdbc.queryForList(
                "SELECT substr(date(rat/1000 + 28800, 'unixepoch'),1,7) m, COUNT(*) n FROM ("
                        + " SELECT " + resolvedAtExpr(doneNames) + " rat FROM ticket t WHERE 1=1" + w.sql
                        + ") WHERE rat IS NOT NULL GROUP BY m", rArgs.toArray());

        TreeSet<String> months = new TreeSet<>();
        for (Map<String, Object> r : created) months.add((String) r.get("m"));
        for (Map<String, Object> r : resolved) months.add((String) r.get("m"));

        Map<String, long[]> byMonth = new LinkedHashMap<>();
        for (String m : months) byMonth.put(m, new long[2]);
        for (Map<String, Object> r : created) byMonth.get(r.get("m"))[0] = asLong(r.get("n"));
        for (Map<String, Object> r : resolved) byMonth.get(r.get("m"))[1] = asLong(r.get("n"));

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, long[]> e : byMonth.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", e.getKey());
            m.put("created", e.getValue()[0]);
            m.put("resolved", e.getValue()[1]);
            list.add(m);
        }
        return list;
    }

    // ---------- 分布 ----------

    /** 产品线分布 [{name, count}]，按工单量倒序取前 limit 名 */
    public List<Map<String, Object>> byProduct(Filter f, int limit) {
        Where w = new Where();
        f.apply(this, w);
        List<Object> args = new ArrayList<>(w.args);
        args.add(limit);
        return jdbc.queryForList(
                "SELECT tp.product_name name, COUNT(DISTINCT t.uuid) n"
                        + " FROM ticket t JOIN ticket_product tp ON tp.ticket_uuid = t.uuid"
                        + " WHERE 1=1" + w.sql
                        + " GROUP BY tp.product_name ORDER BY n DESC, name LIMIT ?", args.toArray());
    }

    /** 功能模块分布 [{name, count}] */
    public List<Map<String, Object>> byModule(Filter f, int limit) {
        Where w = new Where();
        f.apply(this, w);
        List<Object> args = new ArrayList<>(w.args);
        args.add(limit);
        return jdbc.queryForList(
                "SELECT tm.module_name name, COUNT(DISTINCT t.uuid) n"
                        + " FROM ticket t JOIN ticket_module tm ON tm.ticket_uuid = t.uuid"
                        + " WHERE 1=1" + w.sql
                        + " GROUP BY tm.module_name ORDER BY n DESC, name LIMIT ?", args.toArray());
    }

    /** 客户 Top N [{name, count}]（无客户的工单不计入） */
    public List<Map<String, Object>> byCustomer(Filter f, int limit) {
        Where w = new Where();
        f.apply(this, w);
        List<Object> args = new ArrayList<>(w.args);
        args.add(limit);
        return jdbc.queryForList(
                "SELECT t.customer_name name, COUNT(*) n FROM ticket t"
                        + " WHERE t.customer_name IS NOT NULL" + w.sql
                        + " GROUP BY t.customer_name ORDER BY n DESC, name LIMIT ?", args.toArray());
    }

    /** 负责人分布 [{uuid, name, todo, doing, processed, done}]，按合计倒序取前 limit 名 */
    public List<Map<String, Object>> byAssignee(Filter f, int limit) {
        Where w = new Where();
        f.apply(this, w);
        List<Object> args = new ArrayList<>(w.args);
        args.add(limit);
        String cat = bizCategoryExpr();
        return jdbc.queryForList(
                "SELECT t.assign_uuid uuid, t.assign_name name,"
                        + " SUM(CASE WHEN " + cat + "='to_do' THEN 1 ELSE 0 END) todo,"
                        + " SUM(CASE WHEN " + cat + "='in_progress' THEN 1 ELSE 0 END) doing,"
                        + " SUM(CASE WHEN " + cat + "='processed' THEN 1 ELSE 0 END) processed,"
                        + " SUM(CASE WHEN " + cat + "='done' THEN 1 ELSE 0 END) done"
                        + " FROM ticket t WHERE t.assign_name IS NOT NULL" + w.sql
                        + " GROUP BY t.assign_uuid, t.assign_name"
                        + " ORDER BY (todo + doing + processed + done) DESC, name LIMIT ?", args.toArray());
    }

    /** 优先级分布 [{name, count}]，高/中/低固定排前，其余按量倒序排后 */
    public List<Map<String, Object>> byPriority(Filter f) {
        Where w = new Where();
        f.apply(this, w);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.priority name, COUNT(*) n FROM ticket t WHERE 1=1" + w.sql
                        + " GROUP BY t.priority ORDER BY n DESC", w.args.toArray());
        // 中文排序无业务约定，Java 侧按固定顺序重排
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (String p : new String[]{"高", "中", "低"}) {
            for (Map<String, Object> r : rows) {
                if (p.equals(r.get("name"))) { ordered.add(r); break; }
            }
        }
        for (Map<String, Object> r : rows) {
            String name = (String) r.get("name");
            if (!"高".equals(name) && !"中".equals(name) && !"低".equals(name)) ordered.add(r);
        }
        return ordered;
    }

    // ---------- 筛选项下拉数据（不受筛选影响） ----------

    /** 看板筛选区下拉选项 */
    public Map<String, Object> filterOptions() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("products", jdbc.queryForList(
                "SELECT product_name name FROM ticket_product GROUP BY product_name ORDER BY name"));
        m.put("customers", jdbc.queryForList(
                "SELECT customer_name name FROM ticket WHERE customer_name IS NOT NULL"
                        + " GROUP BY customer_name ORDER BY name"));
        m.put("assignees", jdbc.queryForList(
                "SELECT assign_uuid uuid, assign_name name FROM ticket WHERE assign_uuid IS NOT NULL"
                        + " GROUP BY assign_uuid, assign_name ORDER BY name"));
        // 关注人：出现在工单关注人关联表里的人（姓名经 ones_user 缓存解析，解析不到的不展示）
        m.put("watchers", jdbc.queryForList(
                "SELECT tw.watcher_uuid uuid, u.name name FROM ticket_watcher tw"
                        + " JOIN ones_user u ON u.uuid = tw.watcher_uuid"
                        + " GROUP BY tw.watcher_uuid, u.name ORDER BY name"));
        m.put("statuses", jdbc.queryForList(
                "SELECT " + bizCategoryExpr() + " category, t.status_name name FROM ticket t"
                        + " GROUP BY t.status_category, t.status_name ORDER BY category, name"));
        // 解决者：有过「转入已解决类 或 待确认/待发布」流转操作的人（姓名经 ones_user 缓存解析）。
        // 内连接过滤掉缓存中已不存在的账号（离职用户不出现在 users 接口里，姓名解析不到）
        List<Object> n = new ArrayList<>();
        String rn = resolverNamesPlaceholder(n);
        m.put("resolvers", jdbc.queryForList(
                "SELECT h.operator_uuid uuid, u.name name FROM ticket_history h"
                        + " JOIN ones_user u ON u.uuid = h.operator_uuid"
                        + " WHERE h.field_name = '状态' AND h.new_value IN (" + rn + ")"
                        + " GROUP BY h.operator_uuid, u.name ORDER BY name", n.toArray()));
        return m;
    }

    // ---------- 工单下钻列表 ----------

    /**
     * 下钻结果分页（rows + total）。dim/value 决定按哪个图表维度反查：
     * product / module / customer / status / assignee / resolver / priority / month_created / month_resolved
     */
    public Map<String, Object> drill(Filter f, String dim, String value, int page, int size) {
        Where w = new Where();
        f.apply(this, w);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("下钻维度取值不能为空");
        }
        // 「解决记录」派生表：每张工单最后一次转入「已解决类 或 待确认/待发布」状态的时间与操作人
        // （解决者归属口径，SQLite MAX 聚合下裸列取自最大值所在行）；
        // rat_done 单独取「已解决类」最后一次时间（解决时间口径，不含待确认/待发布）。
        // 占位符顺序 = SQL 文本顺序：SELECT 里的 CASE（已解决类名）→ WHERE 里的 IN（归属集状态名）。
        // 操作人姓名经 ones_user 缓存解析
        List<Object> joinArgs = new ArrayList<>();
        String dnOnly = doneNamesPlaceholder(joinArgs);
        List<Object> rnArgs = new ArrayList<>();
        String resolverNames = resolverNamesPlaceholder(rnArgs);
        joinArgs.addAll(rnArgs);
        String resolverJoin = " LEFT JOIN (SELECT ticket_uuid tid, MAX(send_time) rat,"
                + " operator_uuid ruuid,"
                + " MAX(CASE WHEN new_value IN (" + dnOnly + ") THEN send_time END) rat_done"
                + " FROM ticket_history"
                + " WHERE field_name = '状态' AND new_value IN (" + resolverNames + ")"
                + " GROUP BY ticket_uuid) r ON r.tid = t.uuid"
                + " LEFT JOIN ones_user ru ON ru.uuid = r.ruuid";

        List<Object> dimArgs = new ArrayList<>();
        String dimWhere;
        switch (dim == null ? "" : dim) {
            case "product":
                dimWhere = " AND EXISTS (SELECT 1 FROM ticket_product tp"
                        + " WHERE tp.ticket_uuid = t.uuid AND tp.product_name = ?)";
                dimArgs.add(value);
                break;
            case "module":
                dimWhere = " AND EXISTS (SELECT 1 FROM ticket_module tm"
                        + " WHERE tm.ticket_uuid = t.uuid AND tm.module_name = ?)";
                dimArgs.add(value);
                break;
            case "customer":
                dimWhere = " AND t.customer_name = ?";
                dimArgs.add(value);
                break;
            case "status":
                dimWhere = " AND t.status_name = ?";
                dimArgs.add(value);
                break;
            case "assignee":
                dimWhere = " AND t.assign_uuid = ?";
                dimArgs.add(value);
                break;
            case "resolver":
                dimWhere = " AND r.ruuid = ?";
                dimArgs.add(value);
                break;
            case "priority":
                dimWhere = " AND t.priority = ?";
                dimArgs.add(value);
                break;
            case "month_created":
                dimWhere = " AND t.create_time >= ? AND t.create_time < ?";
                addMonthRange(dimArgs, value);
                break;
            case "month_resolved":
                // 解决时间落在该月：最后一次进入已解决类状态的时间（rat_done，不含待确认/待发布）在 [月初, 下月初) 内
                dimWhere = " AND r.rat_done >= ? AND r.rat_done < ?";
                addMonthRange(dimArgs, value);
                break;
            default:
                throw new IllegalArgumentException("不支持的下钻维度：" + dim);
        }

        // 占位符顺序 = SQL 文本顺序：JOIN 里的状态名 → 筛选条件 → 维度条件
        List<Object> args = new ArrayList<>(joinArgs);
        args.addAll(w.args);
        args.addAll(dimArgs);
        String baseFrom = " FROM ticket t" + resolverJoin + " WHERE 1=1" + w.sql + dimWhere;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + baseFrom, Long.class, args.toArray());

        int offset = (page - 1) * size;
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(size);
        listArgs.add(offset);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.uuid, t.number, t.name, t.customer_name, t.status_category, t.status_name,"
                        + " t.priority, t.assign_name, t.create_time, ru.name resolver_name,"
                        + " (SELECT GROUP_CONCAT(tp.product_name, ' / ') FROM ticket_product tp"
                        + "   WHERE tp.ticket_uuid = t.uuid) products"
                        + baseFrom
                        + " ORDER BY t.create_time DESC, t.number DESC LIMIT ? OFFSET ?",
                listArgs.toArray());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total == null ? 0 : total);
        m.put("page", page);
        m.put("size", size);
        m.put("rows", rows);
        return m;
    }

    // ---------- 报告语料（AI 分析用，与看板同一套筛选口径） ----------

    /**
     * 报告/相似簇聚类用工单明细：与看板同一套筛选口径，按创建时间倒序，最多 limit 条。
     * 字段精简为聚类所需（编号/标题/描述/客户/产品线/状态/优先级/负责人/创建时间）。
     */
    public List<Map<String, Object>> reportTickets(Filter f, int limit) {
        Where w = new Where();
        f.apply(this, w);
        List<Object> args = new ArrayList<>(w.args);
        args.add(limit); // 筛选参数在前，LIMIT 在最后
        return jdbc.queryForList(
                "SELECT t.number, t.name, t.description_text, t.customer_name, t.status_name,"
                        + " t.priority, t.assign_name, t.create_time,"
                        + " (SELECT GROUP_CONCAT(tp.product_name, ' / ') FROM ticket_product tp"
                        + "   WHERE tp.ticket_uuid = t.uuid) products"
                        + " FROM ticket t WHERE 1=1" + w.sql
                        + " ORDER BY t.create_time DESC, t.number DESC LIMIT ?", args.toArray());
    }

    // ---------- 内部工具 ----------

    /** 把 'YYYY-MM' 换成 UTC+8 的 [起始毫秒, 结束毫秒) 追加进参数 */
    private static void addMonthRange(List<Object> args, String month) {
        try {
            YearMonth ym = YearMonth.parse(month);
            long start = ym.atDay(1).atStartOfDay(ZoneOffset.ofHours(8)).toInstant().toEpochMilli();
            long end = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.ofHours(8)).toInstant().toEpochMilli();
            args.add(start);
            args.add(end);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("月份格式应为 YYYY-MM：" + month);
        }
    }

    private static long asLong(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
