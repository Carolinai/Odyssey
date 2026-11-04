package org.odyssey.store;

import org.odyssey.model.Feedback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 建议反馈存储层：SQLite 单表，构造时幂等建表。
 * 只增不改；查询无条件时默认按提交时间倒序。
 */
public class FeedbackStore {

    private final JdbcTemplate jdbc;

    public FeedbackStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS feedback ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "username TEXT NOT NULL,"
                + "type TEXT NOT NULL,"        // bug / feature / suggestion
                + "content TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL)"); // Unix 毫秒
    }

    /** 新增一条反馈，返回提交时间（毫秒） */
    public long insert(String username, String type, String content) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO feedback(username, type, content, created_at) VALUES (?, ?, ?, ?)",
                username, type, content, now);
        return now;
    }

    /**
     * 按条件查询反馈；各条件均可为 null（不过滤）。
     * 用户名模糊匹配，类型精确匹配，时间范围按提交时间（毫秒，含边界）。
     * 无排序要求时默认 created_at 倒序（最新在前）。
     */
    public List<Feedback> query(String username, String type, Long fromMs, Long toMs) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, username, type, content, created_at FROM feedback WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (username != null && !username.trim().isEmpty()) {
            sql.append(" AND username LIKE ?");
            args.add("%" + username.trim() + "%");
        }
        if (type != null && !type.trim().isEmpty()) {
            sql.append(" AND type = ?");
            args.add(type.trim());
        }
        if (fromMs != null) {
            sql.append(" AND created_at >= ?");
            args.add(fromMs);
        }
        if (toMs != null) {
            sql.append(" AND created_at <= ?");
            args.add(toMs);
        }
        sql.append(" ORDER BY created_at DESC");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Feedback f = new Feedback();
            f.setId(rs.getLong("id"));
            f.setUserName(rs.getString("username"));
            f.setType(rs.getString("type"));
            f.setContent(rs.getString("content"));
            f.setCreatedAt(rs.getLong("created_at"));
            return f;
        }, args.toArray());
    }
}
