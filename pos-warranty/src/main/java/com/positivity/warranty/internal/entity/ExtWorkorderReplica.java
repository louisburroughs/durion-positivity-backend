package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only workorder header replica fed by {@code workorder.events.v1} (ADR-0044 §6, #924).
 *
 * <p>pos-workorder owns these facts; only the {@code WorkorderEventsListener} consumer writes this
 * table. Serves candidate-line origin search (workorders by customer/vehicle) previously answered
 * by the retired synchronous {@code WorkorderClient}. Part/service lines live in
 * {@link ExtWorkorderLineReplica}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_workorder")
public class ExtWorkorderReplica {

    @Id
    @Column(name = "workorder_id", nullable = false)
    private UUID workorderId;

    @Column(name = "workorder_number", length = 64)
    private String workorderNumber;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "workorder_created_at")
    private Instant workorderCreatedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): PK is a UUIDv7 verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
