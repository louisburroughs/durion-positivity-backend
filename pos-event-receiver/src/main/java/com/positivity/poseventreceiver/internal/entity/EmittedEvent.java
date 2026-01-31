package com.positivity.poseventreceiver.internal.entity;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Entity representing an emitted event stored in the database.
 * Captures event execution metrics including timing and API version.
 */
@Entity
@Data
@Table(name = "emitted_event")
public class EmittedEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long eventId;

    /** The event type identifier (e.g., ORDER_ORDER_CREATE) */
    private final String id;

    /** API version that triggered this event */
    private final String apiVersion;

    /** Timestamp when the event completed */
    private final long timestamp;

    /** Elapsed time in milliseconds for the operation */
    private final long elapsedMs;

    /** When the event was published */
    private final Instant publishedAt;

    public EmittedEvent(String id, String apiVersion, long timestamp, long elapsedMs, Instant publishedAt) {
        this.id = id;
        this.apiVersion = apiVersion;
        this.timestamp = timestamp;
        this.elapsedMs = elapsedMs;
        this.publishedAt = publishedAt;
    }
}
