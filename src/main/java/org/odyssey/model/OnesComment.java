package org.odyssey.model;

/** ONES 工单评论（messages 接口 type=discussion） */
public class OnesComment {

    private String uuid;
    private String ticketUuid;
    private String fromUuid;
    private long sendTimeMs;
    private String text;

    public OnesComment() {
    }

    public OnesComment(String uuid, String ticketUuid, String fromUuid, long sendTimeMs, String text) {
        this.uuid = uuid;
        this.ticketUuid = ticketUuid;
        this.fromUuid = fromUuid;
        this.sendTimeMs = sendTimeMs;
        this.text = text;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getTicketUuid() {
        return ticketUuid;
    }

    public void setTicketUuid(String ticketUuid) {
        this.ticketUuid = ticketUuid;
    }

    public String getFromUuid() {
        return fromUuid;
    }

    public void setFromUuid(String fromUuid) {
        this.fromUuid = fromUuid;
    }

    public long getSendTimeMs() {
        return sendTimeMs;
    }

    public void setSendTimeMs(long sendTimeMs) {
        this.sendTimeMs = sendTimeMs;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
