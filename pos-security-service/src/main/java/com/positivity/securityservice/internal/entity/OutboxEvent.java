package com.positivity.securityservice.internal.entity;

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
                "transactional outbox; the unbound poller publishes every tenant's rows and reads the tenant from the row")
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

    /**
     * Producing tenant, carried as plain data (ADR-0062 §3): the poller runs with no tenant bound and
     * stamps this on the Kafka record header, so the consumer's interceptor binds it before the
     * listener runs. Never a discriminator here: the table is global.
     */
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
