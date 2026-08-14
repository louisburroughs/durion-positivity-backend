package com.positivity.supplier.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Idempotency log for consumed events (ADR-0044 §4), keyed by the envelope's {@code eventId}.
 *
 * <p>At-least-once delivery means a consumer will see the same fact twice; this row is what makes
 * the second delivery a no-op. Unsupported event types are recorded too, so the owner's
 * reconciliation manifest counts every fact in a window rather than reporting the ones this module
 * chose to ignore as missing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    /** Producing domain, per the repo-wide convention (manifest scans key on it). */
    @Column(name = "owner", nullable = false, length = 64)
    private String owner;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
