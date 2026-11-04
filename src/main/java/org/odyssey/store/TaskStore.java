package org.odyssey.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台任务表（task）读写：任务由 {@link org.odyssey.service.TaskExecutor} 执行，
 * 页面通过轮询接口读取状态。状态机 pending → running → done / failed（不复活）。
 */
@Component
public class TaskStore {

    private final JdbcTemplate jdbc;

    public TaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建任务（pending 态），返回自增 id（用 GeneratedKeyHolder 同连接取键，
     *  不能跨连接 SELECT last_insert_rowid()——那是每连接变量，无连接池时必拿错） */
    public long create(String type, String paramsJson, String createdBy) {
        long now = System.currentTimeMillis();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task(type, params_json, status, progress, created_by, created_at)"
                            + " VALUES (?, ?, 'pending', 0, ?, ?)");
            ps.setString(1, type);
            ps.setString(2, paramsJson);
            ps.setString(3, createdBy);
            ps.setLong(4, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("获取任务 id 失败");
        return key.longValue();
    }

    /** 单任务详情（无则 null） */
    public Map<String, Object> get(long id) {
        return jdbc.query("SELECT * FROM task WHERE id = ?",
                rs -> rs.next() ? toMap(rs) : null, id);
    }

    /** 最近任务列表（新在前），供任务列表/轮询页使用 */
    public List<Map<String, Object>> list(int limit) {
        return jdbc.queryForList(
                "SELECT * FROM task ORDER BY id DESC LIMIT ?", limit);
    }

    /** 置为 running 并写初始阶段文案 */
    public void markRunning(long id, String stage) {
        jdbc.update("UPDATE task SET status = 'running', stage = ? WHERE id = ?", stage, id);
    }

    /** 更新进度（0-100）与阶段文案 */
    public void updateProgress(long id, int progress, String stage) {
        jdbc.update("UPDATE task SET progress = ?, stage = ? WHERE id = ?", progress, stage, id);
    }

    public void markDone(long id) {
        jdbc.update("UPDATE task SET status = 'done', progress = 100, finished_at = ? WHERE id = ?",
                System.currentTimeMillis(), id);
    }

    public void markFailed(long id, String error) {
        jdbc.update("UPDATE task SET status = 'failed', error = ?, finished_at = ? WHERE id = ?",
                error, System.currentTimeMillis(), id);
    }

    /** 指定类型的未完成任务（pending/running，旧在前），供终止功能定位目标 */
    public List<Map<String, Object>> unfinishedByType(String type) {
        return jdbc.queryForList(
                "SELECT * FROM task WHERE type = ? AND status IN ('pending', 'running') ORDER BY id", type);
    }

    /** 应用重启时清理：未完成任务一律标记失败（不复活），返回清理条数 */
    public int failAllUnfinished(String reason) {
        return jdbc.update("UPDATE task SET status = 'failed', error = ?, finished_at = ?"
                        + " WHERE status IN ('pending', 'running')",
                reason, System.currentTimeMillis());
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
