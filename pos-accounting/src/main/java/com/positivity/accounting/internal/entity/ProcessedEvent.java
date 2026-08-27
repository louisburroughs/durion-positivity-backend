package com.positivity.accounting.internal.entity;

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
 * Consumer idempotency guard (ADR-0044 §4): one row per processed eventId, inserted in the same
 * transaction as the replica update so Kafka redelivery is harmless.
 *
 * <p>This table is shared by every one of this module's Kafka listeners, so {@link #owner} is
 * nullable and set only by listeners that need to scope a reconciliation-manifest window scan to
 * the events they specifically recorded (issue #1537 D2) — see {@code InvoiceEventsListener} and
 * {@code InvoiceManifestListener}. Listeners that do not reconcile leave it {@code null}.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "owner", length = 64)
    private String owner;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
