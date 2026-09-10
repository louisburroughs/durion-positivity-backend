package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Transactional-outbox row (ADR-0044 §4): a serialized Kafka event written in the same
 * transaction as the business state change and drained by {@code OutboxPublisher}.
 */
@Entity
@TenantGlobal(
        reason =
                "transactional outbox drained by the unbound OutboxPublisher for every tenant; tenant_id carried as data")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "event_outbox")
public class OutboxEvent {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** The producing tenant, carried as data (the table is global) and stamped on the Kafka record header. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String topic;

    @Column(name = "record_key", nullable = false)
    private String recordKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
