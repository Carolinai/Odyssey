package org.odyssey.model;

import java.util.ArrayList;
import java.util.List;

/** 一个 ONES 工单的全部动态流：评论 + 字段变更历史 */
public class OnesTaskMessages {

    private final List<OnesComment> comments = new ArrayList<>();
    private final List<OnesHistory> histories = new ArrayList<>();

    public List<OnesComment> getComments() {
        return comments;
    }

    public List<OnesHistory> getHistories() {
        return histories;
    }
}
