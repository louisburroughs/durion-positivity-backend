package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Idempotency log for event consumers (ADR-0044 §4, plan story D1): one row per applied envelope,
 * keyed by the envelope's {@code eventId} and written in the same transaction as the consumer's
 * state change, so redelivery is harmless. First consumers arrive with the settlement handshake
 * (story C3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    /** Producing domain (e.g. {@code invoice}); scopes reconciliation-manifest window scans. */
    @Column(name = "owner", nullable = false, updatable = false, length = 64)
    private String owner;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** Explicit dependency hook for the ArchUnit UUIDv7 rule: eventId IS a UUIDv7 string. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
