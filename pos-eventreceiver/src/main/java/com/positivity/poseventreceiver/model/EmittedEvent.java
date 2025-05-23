package com.positivity.poseventreceiver.model;

import jakarta.persistence.*;

@Entity
@Table(name = "emitted_event")
public class EmittedEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long eventId;
    private String id;
    private long timestamp;

    public EmittedEvent() {}
    public EmittedEvent(String id, long timestamp) {
        this.id = id;
        this.timestamp = timestamp;
    }
    public Long getEventId() { return eventId; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
