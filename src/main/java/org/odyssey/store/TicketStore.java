package org.odyssey.store;

import org.odyssey.model.OnesComment;
import org.odyssey.model.OnesRef;
import org.odyssey.model.OnesTicket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单助手 SQLite 存取（同步写入 + 同步日志/状态）。
 * 看板统计查询见后续工单（issues #06）。
 *
 * 幂等约定：工单按 uuid UPSERT；产品线/模块/评论/历史按 ticket_uuid 先删后插，
 * 重复同步不产生重复数据。
 */
@Repository
public class TicketStore {

    private static final String TICKET_UPSERT =
            "INSERT INTO ticket(uuid, number, name, description_text, status_category, status_name, "
                    + "priority, assign_uuid, assign_name, owner_uuid, owner_name, customer_uuid, "
                    + "customer_name, create_time, deadline, server_update_stamp, synced_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET "
                    + "number=excluded.number, name=excluded.name, description_text=excluded.description_text, "
                    + "status_category=excluded.status_category, status_name=excluded.status_name, "
                    + "priority=excluded.priority, assign_uuid=excluded.assign_uuid, assign_name=excluded.assign_name, "
                    + "owner_uuid=excluded.owner_uuid, owner_name=excluded.owner_name, "
                    + "customer_uuid=excluded.customer_uuid, customer_name=excluded.customer_name, "
                    + "create_time=excluded.create_time, deadline=excluded.deadline, "
                    + "server_update_stamp=excluded.server_update_stamp, synced_at=excluded.synced_at";

    private final JdbcTemplate jdbc;

    public TicketStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- 工单写入 ----

    /** 写入/更新一条工单及其产品线、产品模块关联 */
    public void upsertTicket(OnesTicket t, long syncedAt) {
        jdbc.update(TICKET_UPSERT,
                t.getUuid(), t.getNumber(), t.getName(), t.getDescriptionText(),
                t.getStatusCategory(), t.getStatusName(), t.getPriority(),
                t.getAssignUuid(), t.getAssignName(), t.getOwnerUuid(), t.getOwnerName(),
                t.getCustomerUuid(), t.getCustomerName(), t.getCreateTimeMs(), t.getDeadline(),
                t.getServerUpdateStampMs(), syncedAt);

        jdbc.update("DELETE FROM ticket_product WHERE ticket_uuid = ?", t.getUuid());
        for (OnesRef p : t.getProducts()) {
            jdbc.update("INSERT OR IGNORE INTO ticket_product(ticket_uuid, product_uuid, product_name) VALUES (?,?,?)",
                    t.getUuid(), p.getUuid(), p.getName());
        }
        jdbc.update("DELETE FROM ticket_module WHERE ticket_uuid = ?", t.getUuid());
        for (OnesRef m : t.getModules()) {
            jdbc.update("INSERT OR IGNORE INTO ticket_module(ticket_uuid, module_uuid, module_name) VALUES (?,?,?)",
                    t.getUuid(), m.getUuid(), m.getName());
        }
        jdbc.update("DELETE FROM ticket_watcher WHERE ticket_uuid = ?", t.getUuid());
        for (OnesRef w : t.getWatchers()) {
            jdbc.update("INSERT OR IGNORE INTO ticket_watcher(ticket_uuid, watcher_uuid) VALUES (?,?)",
                    t.getUuid(), w.getUuid());
        }
    }

    /** 重写一条工单的动态流（评论 + 历史），评论人姓名经 userNames 解析 */
    public void replaceMessages(String ticketUuid, List<OnesComment> comments,
                                List<org.odyssey.model.OnesHistory> histories,
                                Map<String, String> userNames) {
        jdbc.update("DELETE FROM ticket_comment WHERE ticket_uuid = ?", ticketUuid);
        for (OnesComment c : comments) {
            jdbc.update("INSERT OR REPLACE INTO ticket_comment(uuid, ticket_uuid, from_uuid, from_name, send_time, text) VALUES (?,?,?,?,?,?)",
                    c.getUuid(), ticketUuid, c.getFromUuid(),
                    c.getFromUuid() == null ? null : userNames.get(c.getFromUuid()),
                    c.getSendTimeMs(), c.getText());
        }
        jdbc.update("DELETE FROM ticket_history WHERE ticket_uuid = ?", ticketUuid);
        for (org.odyssey.model.OnesHistory h : histories) {
            jdbc.update("INSERT INTO ticket_history(ticket_uuid, send_time, operator_uuid, field_name, old_value, new_value) VALUES (?,?,?,?,?,?)",
                    h.getTicketUuid(), h.getSendTimeMs(), h.getOperatorUuid(),
                    h.getFieldName(), h.getOldValue(), h.getNewValue());
        }
    }

    /** 更新 ONES 用户缓存（uuid → 姓名） */
    public void upsertUsers(Map<String, String> userNames) {
        for (Map.Entry<String, String> e : userNames.entrySet()) {
            jdbc.update("INSERT OR REPLACE INTO ones_user(uuid, name) VALUES (?,?)",
                    e.getKey(), e.getValue());
        }
    }

    // ---- 同步状态（key-value） ----

    public String getState(String key) {
        return jdbc.query("SELECT value FROM sync_state WHERE key = ?",
                rs -> rs.next() ? rs.getString(1) : null, key);
    }

    public void setState(String key, String value) {
        jdbc.update("INSERT INTO sync_state(key, value) VALUES (?,?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                key, value);
    }

    // ---- 同步日志 ----

    /** 开始一次同步（status=running），返回日志 id */
    public long startSyncLog(String type, String triggerType, long startedAt) {
        jdbc.update("INSERT INTO sync_log(type, trigger_type, status, started_at) VALUES (?,?,?,?)",
                type, triggerType, "running", startedAt);
        return jdbc.queryForObject("SELECT id FROM sync_log ORDER BY id DESC LIMIT 1", Long.class);
    }

    /** 结束一次同步：done / failed（failed 时 error 非空） */
    public void finishSyncLog(long id, String status, int ticketCount, int commentCount,
                              int historyCount, long finishedAt, String error) {
        jdbc.update("UPDATE sync_log SET status = ?, finished_at = ?, ticket_count = ?, "
                        + "comment_count = ?, history_count = ?, error = ? WHERE id = ?",
                status, finishedAt, ticketCount, commentCount, historyCount, error, id);
    }

    /** 最近同步日志（时间倒序） */
    public List<Map<String, Object>> recentSyncLogs(int limit) {
        return jdbc.queryForList(
                "SELECT id, type, trigger_type, status, started_at, finished_at, "
                        + "ticket_count, comment_count, history_count, error "
                        + "FROM sync_log ORDER BY id DESC LIMIT ?", limit);
    }
}
