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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entity representing an emitted event stored in the database.
 * Captures event execution metrics including timing and API version.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "emitted_event")
public class EmittedEvent {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "event_id", columnDefinition = "UUID")
    private UUID eventId;

    /** The event type identifier (e.g., ORDER_ORDER_CREATE) */
    @Column
    private String id;

    /** API version that triggered this event */
    @Column(name = "api_version")
    private String apiVersion;

    /** Timestamp when the event completed */
    @Column
    private long timestamp;

    /** Elapsed time in milliseconds for the operation */
    @Column(name = "elapsed_ms")
    private long elapsedMs;

    /** When the event was published */
    @Column(name = "published_at")
    private Instant publishedAt;

    public EmittedEvent(String id, String apiVersion, long timestamp, long elapsedMs, Instant publishedAt) {
        this.id = id;
        this.apiVersion = apiVersion;
        this.timestamp = timestamp;
        this.elapsedMs = elapsedMs;
        this.publishedAt = publishedAt;
    }
}
