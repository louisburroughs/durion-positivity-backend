package com.positivity.poseventreceiver.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Entity representing an emitted event stored in the database.
 * Captures event execution metrics including timing and API version.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@Table(name = "emitted_event")
public class EmittedEvent {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID eventId;
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
