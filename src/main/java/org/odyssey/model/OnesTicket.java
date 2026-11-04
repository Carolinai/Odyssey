package org.odyssey.model;

import java.util.ArrayList;
import java.util.List;

/**
 * ONES 工单（「工单」类型任务）的本地视图：由 OnesClient 从 GraphQL 拉取并解析。
 * 时间字段统一为 Unix 毫秒（ONES 原始微秒在解析时换算）。
 */
public class OnesTicket {

    private String uuid;
    private int number;
    private String name;
    private String descriptionText;
    private String statusCategory;   // to_do / in_progress / done
    private String statusName;
    private String priority;
    private String assignUuid;
    private String assignName;
    private String ownerUuid;
    private String ownerName;
    /** ONES 自定义字段「客户（QDT）」(_AST3JVZw)：uuid + 客户名 */
    private String customerUuid;
    private String customerName;
    private long createTimeMs;
    /** ONESDATE 格式的截止日期（yyyy-MM-dd），可为 null */
    private String deadline;
    private long serverUpdateStampMs;
    /** 产品线（多值） */
    private List<OnesRef> products = new ArrayList<>();
    /** 产品模块（多值） */
    private List<OnesRef> modules = new ArrayList<>();
    /** 关注人（多值） */
    private List<OnesRef> watchers = new ArrayList<>();

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescriptionText() {
        return descriptionText;
    }

    public void setDescriptionText(String descriptionText) {
        this.descriptionText = descriptionText;
    }

    public String getStatusCategory() {
        return statusCategory;
    }

    public void setStatusCategory(String statusCategory) {
        this.statusCategory = statusCategory;
    }

    public String getStatusName() {
        return statusName;
    }

    public void setStatusName(String statusName) {
        this.statusName = statusName;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getAssignUuid() {
        return assignUuid;
    }

    public void setAssignUuid(String assignUuid) {
        this.assignUuid = assignUuid;
    }

    public String getAssignName() {
        return assignName;
    }

    public void setAssignName(String assignName) {
        this.assignName = assignName;
    }

    public String getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(String ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getCustomerUuid() {
        return customerUuid;
    }

    public void setCustomerUuid(String customerUuid) {
        this.customerUuid = customerUuid;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public long getCreateTimeMs() {
        return createTimeMs;
    }

    public void setCreateTimeMs(long createTimeMs) {
        this.createTimeMs = createTimeMs;
    }

    public String getDeadline() {
        return deadline;
    }

    public void setDeadline(String deadline) {
        this.deadline = deadline;
    }

    public long getServerUpdateStampMs() {
        return serverUpdateStampMs;
    }

    public void setServerUpdateStampMs(long serverUpdateStampMs) {
        this.serverUpdateStampMs = serverUpdateStampMs;
    }

    public List<OnesRef> getProducts() {
        return products;
    }

    public void setProducts(List<OnesRef> products) {
        this.products = products;
    }

    public List<OnesRef> getModules() {
        return modules;
    }

    public void setModules(List<OnesRef> modules) {
        this.modules = modules;
    }

    public List<OnesRef> getWatchers() {
        return watchers;
    }

    public void setWatchers(List<OnesRef> watchers) {
        this.watchers = watchers;
    }
}
