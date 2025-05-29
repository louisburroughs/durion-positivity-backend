package com.positivity.poseventreceiver.api;

public class EmitEventRequest {
    private String id;
    private long timestamp;

    public EmitEventRequest() {}
    public EmitEventRequest(String id, long timestamp) {
        this.id = id;
        this.timestamp = timestamp;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
