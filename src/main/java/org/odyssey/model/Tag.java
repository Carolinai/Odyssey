package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 客户标签：名字 + 自选颜色（展示在列表卡片和详情页）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Tag {

    private String name;

    /** 颜色值，如 #e74c3c */
    private String color;

    public Tag() {
    }

    public Tag(String name, String color) {
        this.name = name;
        this.color = color;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
