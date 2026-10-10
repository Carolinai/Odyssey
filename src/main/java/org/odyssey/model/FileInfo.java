package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 客户附件元数据：文件实体存放在 files/&lt;客户名&gt;/ 下，YAML 只记元信息。
 * 典型用途：特定 VPN 软件安装包、使用文档等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FileInfo {

    /** 原始文件名（下载时还原） */
    private String name;

    /** 服务器上的存储名（UUID，避免中文/重名问题） */
    private String storedName;

    /** 文件大小（字节） */
    private long size;

    /** 上传时间（yyyy-MM-dd HH:mm） */
    private String uploadedAt;

    /** 用途备注：如 "VPN 软件"、"使用文档" */
    private String note;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStoredName() {
        return storedName;
    }

    public void setStoredName(String storedName) {
        this.storedName = storedName;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(String uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
