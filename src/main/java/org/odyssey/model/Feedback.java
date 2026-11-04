package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 一条建议反馈：使用者在系统上提交的问题（Bug）、需求与建议。
 * 存 SQLite feedback 表，提交后不可修改。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Feedback {

    /** 反馈类型：bug（问题）/ feature（需求）/ suggestion（建议） */
    public static final String TYPE_BUG = "bug";
    public static final String TYPE_FEATURE = "feature";
    public static final String TYPE_SUGGESTION = "suggestion";

    private Long id;

    /** 提交人名称（登录时的中文显示名，无则回落到账号） */
    private String userName;

    /** 反馈类型：bug / feature / suggestion */
    private String type;

    /** 反馈内容 */
    private String content;

    /** 提交时间（Unix 毫秒） */
    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
