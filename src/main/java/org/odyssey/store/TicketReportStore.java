package org.odyssey.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 分析报告表（ai_report）读写：报告行随后台任务创建（running 态），
 * 生成成功写入正文置 done，失败写入原因置 failed（与 task 表状态各自独立、互为明细）。
 */
@Component
public class TicketReportStore {

    private final JdbcTemplate jdbc;

    public TicketReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 任务开始时创建报告行（running 态），返回自增 id（同 TaskStore.create 的取键方式） */
    public long create(String paramsJson, String createdBy) {
        long now = System.currentTimeMillis();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ai_report(params_json, status, created_by, created_at)"
                            + " VALUES (?, 'running', ?, ?)");
            ps.setString(1, paramsJson);
            ps.setString(2, createdBy);
            ps.setLong(3, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("获取报告 id 失败");
        return key.longValue();
    }

    /** 生成成功：写入正文并置 done */
    public void markDone(long id, String contentMd) {
        jdbc.update("UPDATE ai_report SET status = 'done', content_md = ?, finished_at = ? WHERE id = ?",
                contentMd, System.currentTimeMillis(), id);
    }

    /** 生成失败：写入原因并置 failed */
    public void markFailed(long id, String error) {
        jdbc.update("UPDATE ai_report SET status = 'failed', error = ?, finished_at = ? WHERE id = ?",
                error, System.currentTimeMillis(), id);
    }

    /** 单报告详情（含正文；无则 null） */
    public Map<String, Object> get(long id) {
        return jdbc.query("SELECT * FROM ai_report WHERE id = ?",
                rs -> rs.next() ? toMap(rs) : null, id);
    }

    /** 最近报告列表（新在前，不含正文大字段，另带正文长度） */
    public List<Map<String, Object>> list(int limit) {
        return jdbc.queryForList(
                "SELECT id, params_json, status, error, created_by, created_at, finished_at,"
                        + " LENGTH(content_md) content_length FROM ai_report ORDER BY id DESC LIMIT ?", limit);
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
