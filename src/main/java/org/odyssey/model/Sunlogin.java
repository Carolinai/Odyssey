package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * 远程方式连接信息：方式（向日葵 / Todesk）+ 连接码 + 验证密码。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Sunlogin {

    /** 远程方式：向日葵 / Todesk（老数据无此字段，默认向日葵） */
    private String type;

    /** 连接码（设备 ID） */
    private String id;

    /** 验证码/密码 */
    private String password;

    /** 备注（如：该机器由谁值守、连接时段等） */
    private String notes;

    /** 相关截图（服务器信息/页面截图等），存图片文件名，文件位于数据目录 images/ 下 */
    private List<String> images;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }
}
