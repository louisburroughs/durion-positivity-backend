package com.positivity.poseventreceiver.entity;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "emitted_event")
public class EmittedEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long eventId;
    private final String id;
    private final long timestamp;
    private final Instant publishedAt;

    public EmittedEvent(String id, long timestamp, Instant publishedAt) {
        this.id = id;
        this.timestamp = timestamp;
        this.publishedAt = publishedAt;
    }

}
