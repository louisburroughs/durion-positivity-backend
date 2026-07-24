package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
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
 * Read-only workorder replica (parity story E1, spec R7.1): fed by
 * {@code workorder.workorder.updated} on {@code workorder.events.v1}; consulted by the
 * source-document import to validate the referenced workorder. pos-workorder remains the system
 * of record.
 */
@Entity
@Table(name = "ext_workorder")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtWorkorder {

    @Id
    @Column(name = "workorder_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID workorderId;

    @Column(length = 64)
    private String workorderNumber;

    @Column(length = 40)
    private String status;

    @Column(columnDefinition = "UUID")
    private UUID customerId;

    @Column(columnDefinition = "UUID")
    private UUID vehicleId;

    @Column(columnDefinition = "UUID")
    private UUID shopId;

    @Column(columnDefinition = "UUID")
    private UUID invoiceId;

    /** Envelope aggregateVersion of the last applied event — stale-event guard. */
    @Column(nullable = false)
    private long aggregateVersion;

    @Column(nullable = false)
    private Instant syncedAt;

    /** ArchUnit UUIDv7 rule hook: workorderId is a UUIDv7 issued by pos-workorder. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
