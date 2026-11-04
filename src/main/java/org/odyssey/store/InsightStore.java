package org.odyssey.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解决经验表（insight）读写：一工单一经验（ticket_uuid UNIQUE），重跑按工单粒度幂等——
 * 已有 done 经验的工单跳过，失败的不入库（下次重跑再试）。
 */
@Component
public class InsightStore {

    private final JdbcTemplate jdbc;

    public InsightStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入/覆盖一条经验（状态 done） */
    public void upsert(String ticketUuid, String customerName, String productName,
                       String contentMd, String model) {
        jdbc.update("INSERT INTO insight(ticket_uuid, customer_name, product_name, content_md, model, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'done', ?)"
                        + " ON CONFLICT(ticket_uuid) DO UPDATE SET customer_name = excluded.customer_name,"
                        + " product_name = excluded.product_name, content_md = excluded.content_md,"
                        + " model = excluded.model, status = 'done', created_at = excluded.created_at",
                ticketUuid, customerName, productName, contentMd, model, System.currentTimeMillis());
    }

    /**
     * 待提炼工单：已解决类且尚无 done 经验（含上次失败未入库的），按创建时间倒序。
     * 字段供提炼语料与经验落库用（products 为产品线聚合）。
     */
    public List<Map<String, Object>> pendingTickets(int limit) {
        return jdbc.queryForList(pendingSql()
                + " ORDER BY t.create_time DESC, t.number DESC LIMIT ?", limit);
    }

    /** 待提炼工单（限定 uuid 集合，供同步后自动增量）；uuid 分块避免 SQLite 参数上限 */
    public List<Map<String, Object>> pendingForUuids(List<String> uuids, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < uuids.size() && result.size() < limit; i += 500) {
            List<String> chunk = uuids.subList(i, Math.min(i + 500, uuids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>(chunk);
            args.add(limit - result.size());
            result.addAll(jdbc.queryForList(
                    pendingSql() + " AND t.uuid IN (" + placeholders + ")"
                            + " ORDER BY t.create_time DESC, t.number DESC LIMIT ?", args.toArray()));
        }
        return result;
    }

    private static String pendingSql() {
        return "SELECT t.uuid, t.number, t.name, t.description_text, t.customer_name, t.status_name,"
                + " t.assign_name,"
                + " (SELECT GROUP_CONCAT(tp.product_name, ' / ') FROM ticket_product tp"
                + "   WHERE tp.ticket_uuid = t.uuid) products"
                + " FROM ticket t LEFT JOIN insight i ON i.ticket_uuid = t.uuid AND i.status = 'done'"
                + " WHERE t.status_category = 'done' AND i.id IS NULL";
    }

    /** 待提炼数量（页面展示全量进度用） */
    public long countPending() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket t"
                        + " LEFT JOIN insight i ON i.ticket_uuid = t.uuid AND i.status = 'done'"
                        + " WHERE t.status_category = 'done' AND i.id IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    /** 工单的解决相关评论（提炼语料，按时间正序取前 20 条） */
    public List<Map<String, Object>> comments(String ticketUuid) {
        return jdbc.queryForList(
                "SELECT from_name, text FROM ticket_comment WHERE ticket_uuid = ?"
                        + " ORDER BY send_time LIMIT 20", ticketUuid);
    }

    /**
     * 经验库分页列表：客户/产品线精确、关键词模糊（覆盖经验正文与源工单标题/描述）。
     * 返回 total + rows（带源工单号与标题，页面跳转 ONES 用 uuid，展示用 number/name）。
     */
    public Map<String, Object> list(String customer, String product, String q, int page, int size) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (customer != null && !customer.trim().isEmpty()) {
            where.append(" AND i.customer_name = ?");
            args.add(customer.trim());
        }
        if (product != null && !product.trim().isEmpty()) {
            where.append(" AND i.product_name = ?");
            args.add(product.trim());
        }
        if (q != null && !q.trim().isEmpty()) {
            String like = "%" + q.trim() + "%";
            where.append(" AND (i.content_md LIKE ? OR t.name LIKE ? OR t.description_text LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM insight i JOIN ticket t ON t.uuid = i.ticket_uuid WHERE 1=1" + where,
                Long.class, args.toArray());
        List<Object> rowsArgs = new ArrayList<>(args);
        rowsArgs.add(size);
        rowsArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.id, i.ticket_uuid, i.customer_name, i.product_name, i.model,"
                        + " LENGTH(i.content_md) content_length, i.created_at,"
                        + " t.number ticket_number, t.name ticket_name, t.status_name"
                        + " FROM insight i JOIN ticket t ON t.uuid = i.ticket_uuid"
                        + " WHERE 1=1" + where + " ORDER BY i.id DESC LIMIT ? OFFSET ?",
                rowsArgs.toArray());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total == null ? 0 : total);
        m.put("page", page);
        m.put("size", size);
        m.put("rows", rows);
        return m;
    }

    /** 单条经验详情（含正文）；无则 null */
    public Map<String, Object> get(long id) {
        return jdbc.query("SELECT i.*, t.number ticket_number, t.name ticket_name"
                        + " FROM insight i JOIN ticket t ON t.uuid = i.ticket_uuid WHERE i.id = ?",
                rs -> rs.next() ? toMap(rs) : null, id);
    }

    /** 经验库筛选项：已有经验的客户与产品线（下拉） */
    public Map<String, Object> options() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customers", jdbc.queryForList(
                "SELECT customer_name name FROM insight WHERE customer_name IS NOT NULL"
                        + " GROUP BY customer_name ORDER BY name"));
        m.put("products", jdbc.queryForList(
                "SELECT product_name name FROM insight WHERE product_name IS NOT NULL"
                        + " GROUP BY product_name ORDER BY name"));
        return m;
    }

    private static Map<String, Object> toMap(java.sql.ResultSet rs) {
        try {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                m.put(md.getColumnLabel(i), rs.getObject(i));
            }
            return m;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
