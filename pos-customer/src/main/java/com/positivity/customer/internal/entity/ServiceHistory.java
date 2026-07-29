package com.positivity.customer.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A completed service on a party's vehicle, projected from {@code workorder.events.v1} (FI-3,
 * #1133). One row per completed workorder.
 *
 * <p>This is a read model owned entirely by the {@code WorkorderEventsListener}; pos-workorder owns
 * the underlying fact (ADR-0044 R6). It gives CRM the service-history signal it previously lacked —
 * the resolver reads the most recent completion per party for last-service-age and service-due
 * predicates. {@code sourceEventId} carries a unique index so a replayed fact cannot duplicate a
 * row, independent of the {@code processed_events} log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "service_history",
        indexes = {
            @Index(name = "idx_service_history_party", columnList = "party_id, completed_at DESC"),
            @Index(name = "idx_service_history_vehicle", columnList = "vehicle_id, completed_at DESC")
        })
public class ServiceHistory {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "service_history_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID serviceHistoryId;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false)
    private UUID partyId;

    @Column(name = "vehicle_id", columnDefinition = "UUID")
    @Nullable
    private UUID vehicleId;

    @Column(name = "source_workorder_id", columnDefinition = "UUID", nullable = false)
    private UUID sourceWorkorderId;

    @Column(name = "workorder_number", length = 64)
    @Nullable
    private String workorderNumber;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "amount", precision = 19, scale = 2)
    @Nullable
    private BigDecimal amount;

    /** Originating event envelope id; the ingestion idempotency key. */
    @Column(name = "source_event_id", length = 64, nullable = false, updatable = false)
    private String sourceEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
