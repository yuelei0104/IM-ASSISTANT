package com.yuelei.imchat.model;

public class OutboxRow {
    private long id;
    private String eventKey;
    private String payload;

    public OutboxRow() { }
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
