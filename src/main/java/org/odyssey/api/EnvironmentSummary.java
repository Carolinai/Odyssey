package org.odyssey.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.odyssey.model.Tag;

import java.util.List;

/**
 * 客户摘要：搜索/列表页只需 name、alias、tags。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EnvironmentSummary {

    private String name;

    private List<String> alias;

    private List<Tag> tags;

    public EnvironmentSummary() {
    }

    public EnvironmentSummary(String name, List<String> alias, List<Tag> tags) {
        this.name = name;
        this.alias = alias;
        this.tags = tags;
    }

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
}
