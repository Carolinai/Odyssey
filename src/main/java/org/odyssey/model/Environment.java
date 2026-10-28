package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * 客户信息汇总：对应一个环境 YAML 文件（spec 第 4 节数据模型，2026-09-28 重构）。
 * 一个客户一条记录，内含测试/验证/生产三个环境块（可缺省）。
 * 所有字段均可选，null 字段序列化时省略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Environment {

    /** 客户名（搜索主键，唯一） */
    private String name;

    /** 别名/俗称，工单里常见的叫法 */
    private List<String> alias;

    /** 客户标签（名字 + 自选颜色） */
    private List<Tag> tags;

    /** 各环境块，key 固定为 test / validation / prod */
    private Map<String, EnvBlock> environments;

    /** 客户附件（VPN 软件、使用文档等），文件实体在 files/<客户名>/ 下 */
    private List<FileInfo> files;

    /** 创建时间（yyyy-MM-dd HH:mm） */
    private String createdAt;

    /** 更新时间（yyyy-MM-dd HH:mm），每次保存刷新，列表页按此倒序展示 */
    private String updatedAt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getAlias() {
        return alias;
    }

    public void setAlias(List<String> alias) {
        this.alias = alias;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public Map<String, EnvBlock> getEnvironments() {
        return environments;
    }

    public void setEnvironments(Map<String, EnvBlock> environments) {
        this.environments = environments;
    }

    public List<FileInfo> getFiles() {
        return files;
    }

    public void setFiles(List<FileInfo> files) {
        this.files = files;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
