package org.odyssey.model;

/** ONES 工单字段变更历史（messages 接口 type=system 的 update 记录） */
public class OnesHistory {

    private String ticketUuid;
    private long sendTimeMs;
    private String operatorUuid;
    private String fieldName;
    private String oldValue;
    private String newValue;

    public OnesHistory() {
    }

    public OnesHistory(String ticketUuid, long sendTimeMs, String operatorUuid,
                       String fieldName, String oldValue, String newValue) {
        this.ticketUuid = ticketUuid;
        this.sendTimeMs = sendTimeMs;
        this.operatorUuid = operatorUuid;
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public String getTicketUuid() {
        return ticketUuid;
    }

    public void setTicketUuid(String ticketUuid) {
        this.ticketUuid = ticketUuid;
    }

    public long getSendTimeMs() {
        return sendTimeMs;
    }

    public void setSendTimeMs(long sendTimeMs) {
        this.sendTimeMs = sendTimeMs;
    }

    public String getOperatorUuid() {
        return operatorUuid;
    }

    public void setOperatorUuid(String operatorUuid) {
        this.operatorUuid = operatorUuid;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }
}
