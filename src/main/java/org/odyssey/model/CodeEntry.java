package org.odyssey.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 该环境部署的项目代码分支。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeEntry {

    private String project;

    private String branch;

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }
}
