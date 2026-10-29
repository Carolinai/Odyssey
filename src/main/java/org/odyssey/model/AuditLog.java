package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 一条审计日志：用户在系统上的操作留痕。
 * 追加写入 <dataDir>/audit/audit.jsonl，一行一条 JSON，不修改历史。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AuditLog {

    /** 操作账号（Jenkins 账号，唯一标识） */
    private String user;

    /** 用户名称（Jenkins 显示名，中文姓名） */
    private String userName;

    /** 操作时间（yyyy-MM-dd HH:mm:ss） */
    private String time;

    /** 操作对象（数据类别，如「客户信息」） */
    private String target;

    /** 对象名称（具体是哪一条，如客户名） */
    private String targetName;

    /** 操作事件（新增 / 修改 / 删除） */
    private String event;

    /** 操作描述（细粒度变更内容） */
    private String description;

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
