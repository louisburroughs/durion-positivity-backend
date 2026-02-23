package com.positivity.accounting.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

import com.positivity.shared.id.UUIDv7Id;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Outbox pattern implementation for reliable event delivery.
 * <p>
 * Events are persisted in the same transaction as business operations,
 * ensuring atomicity. A background processor polls pending events and publishes
 * them,
 * providing at-least-once delivery semantics.
 * <p>
 * Lifecycle: PENDING → PUBLISHED (or FAILED after max retries)
 * 
 * @see <a href=
 *      "https://microservices.io/patterns/data/transactional-outbox.html">
 *      Transactional Outbox Pattern</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "event_outbox", indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status,created_at"),
        @Index(name = "idx_outbox_aggregate", columnList = "aggregate_type,aggregate_id"),
        @Index(name = "idx_outbox_event_type", columnList = "event_type")
})
public class EventOutbox {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "outbox_id", nullable = false, columnDefinition = "UUID")
    private UUID outboxId;

    /**
     * Event UUID - serves as idempotency key for duplicate detection.
     */
    @Column(name = "event_id", nullable = false, unique = true, columnDefinition = "UUID")
    private UUID eventId;

    /**
     * Aggregate type (e.g., "APPayment", "JournalEntry")
     */
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    /**
     * Aggregate ID (e.g., paymentId)
     */
    @Column(name = "aggregate_id", nullable = false, columnDefinition = "UUID")
    private UUID aggregateId;

    /**
     * Event type (e.g., "APPaymentGLPostingEvent")
     */
    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    /**
     * JSON payload of the event
     */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * Outbox processing status
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    /**
     * Number of publication attempts
     */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /**
     * Last error message if publication failed
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * When the event was created
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * When the event was successfully published
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Last attempt timestamp
     */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            }
        if (status == null) {
            status = OutboxStatus.PENDING;
        }
    }

    /**
     * Factory method for creating new outbox entries
     */
    public static @NonNull EventOutbox create(
            @NonNull UUID eventId,
            @NonNull String aggregateType,
            @NonNull UUID aggregateId,
            @NonNull String eventType,
            @NonNull String payload) {
        EventOutbox outbox = new EventOutbox();
        outbox.eventId = eventId;
        outbox.aggregateType = aggregateType;
        outbox.aggregateId = aggregateId;
        outbox.eventType = eventType;
        outbox.payload = payload;
        outbox.status = OutboxStatus.PENDING;
        outbox.retryCount = 0;
        return outbox;
    }

    public enum OutboxStatus {
        /** Awaiting publication */
        PENDING,
        /** Successfully published */
        PUBLISHED,
        /** Failed after max retries */
        FAILED
    }
}
