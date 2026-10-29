package org.odyssey.service;

import org.odyssey.model.AuditLog;
import org.odyssey.model.Environment;
import org.odyssey.store.AuditStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * 审计日志服务：记录用户操作（追加写，每个变更字段一条），查询时倒序返回。
 */
@Service
public class AuditService {

    /** 操作对象：客户信息 */
    public static final String TARGET_ENVIRONMENT = "客户信息";

    public static final String EVENT_CREATE = "新增";
    public static final String EVENT_UPDATE = "修改";
    public static final String EVENT_DELETE = "删除";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuditStore store;

    public AuditService(AuditStore store) {
        this.store = store;
    }

    /** 记录一条操作日志（user=账号，userName=用户名称/中文显示名） */
    public void record(String user, String userName, String event,
                       String targetName, String module, String description) {
        AuditLog entry = new AuditLog();
        entry.setUser(user);
        entry.setUserName(userName);
        entry.setTime(TIME_FORMAT.format(LocalDateTime.now()));
        entry.setTarget(TARGET_ENVIRONMENT);
        entry.setTargetName(targetName);
        entry.setEvent(event);
        entry.setModule(module);
        entry.setDescription(description);
        store.append(entry);
    }

    /**
     * 按变更记录整份客户数据的审计日志：before/after 传 null 表示整条新增/删除。
     * 每个发生变化的字段记一条，操作事件即该字段自己的动作。返回记录条数。
     */
    public int recordEnvironmentChanges(String user, String userName, String targetName,
                                        Environment before, Environment after) {
        List<EnvironmentDiff.Change> changes = EnvironmentDiff.changes(before, after);
        for (EnvironmentDiff.Change change : changes) {
            String description;
            if (EVENT_UPDATE.equals(change.getAction())) {
                description = change.getOldValue() + " → " + change.getNewValue();
            } else if (EVENT_DELETE.equals(change.getAction())) {
                description = change.getOldValue();
            } else {
                description = change.getNewValue();
            }
            record(user, userName, change.getAction(), targetName, change.getModule(), description);
        }
        return changes.size();
    }

    /** 查询审计日志（时间倒序）；targetName 非空时只看该对象 */
    public List<AuditLog> list(String targetName) {
        List<AuditLog> all = store.readAll();
        Collections.reverse(all);
        if (targetName == null || targetName.trim().isEmpty()) {
            return all;
        }
        List<AuditLog> result = new java.util.ArrayList<>();
        for (AuditLog entry : all) {
            if (targetName.equals(entry.getTargetName())) {
                result.add(entry);
            }
        }
        return result;
    }
}
