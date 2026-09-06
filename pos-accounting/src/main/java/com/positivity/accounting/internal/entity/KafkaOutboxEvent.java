package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Transactional-outbox row for the Kafka facts pos-accounting publishes (ADR-0044 §4, issue
 * #1843): a serialized {@code DomainEventEnvelope} written in the same transaction as the
 * business state change and drained to {@code accounting.events.v1} by {@code OutboxPublisher}.
 *
 * <p>Distinct from {@link EventOutbox} / {@code event_outbox}, which is this module's in-process
 * Spring-event outbox (drained by {@code OutboxProcessor}) and unrelated to Kafka.
 * {@code createdAt} is stamped by JPA auditing (ADR-0024) from the module's clock-backed
 * {@code DateTimeProvider} (docs/CLOCK_TIMESTAMP_OWNERSHIP.md).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "kafka_event_outbox")
public class KafkaOutboxEvent {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "record_key", nullable = false)
    private String recordKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
