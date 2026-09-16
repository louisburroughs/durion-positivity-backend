package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only workorder replica fed by {@code workorder.events.v1}
 * ({@code workorder.workorder.updated}, ADR-0044 §6, #1658).
 *
 * <p>This table is why {@code GET /v1/shop-dashboard} can answer in a fixed number of queries.
 * pos-workorder owns every fact here — status, the resource a job occupies, its technicians;
 * ADR-0044 R1 forbids reading them synchronously and R3 forbids anything but the event consumer
 * writing this table, so {@link
 * com.positivity.shopmanager.internal.service.WorkorderEventsListener} is its only writer.
 *
 * <p>Rows are kept for terminal workorders too. The dashboard filters on
 * {@link #status} against the owner's open-status set rather than deleting closed rows, which is
 * what makes "a COMPLETED or CANCELLED workorder frees its unit" a pure read-side consequence with
 * no write on either side of the wall.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_workorder")
public class ExtWorkorderReplica extends TenantScopedEntity {

    @Id
    @Column(name = "workorder_id", nullable = false)
    private UUID workorderId;

    @Column(name = "workorder_number")
    private String workorderNumber;

    /** Owner's {@code WorkorderStatus} name, stored verbatim — this module never re-interprets it. */
    @Column(name = "status", length = 32)
    private String status;

    /** The site the work is performed at; the dashboard scopes every query by this column. */
    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "vehicle_id")
    private UUID vehicleId;

    @Column(name = "customer_id")
    private UUID customerId;

    /** The bay or mobile unit the workorder occupies; null while unassigned (a valid DRAFT state). */
    @Column(name = "resource_id")
    private UUID resourceId;

    /** {@code BAY} or {@code MOBILE_UNIT}, from the owner's discriminator (#1656). */
    @Column(name = "resource_type", length = 32)
    private String resourceType;

    /**
     * JSON array of assigned technician person ids, snapshotted from the fact. Stored as the
     * owner's own array shape because a workorder may carry more than one technician and this
     * module must not invent a normalized join table for a fact it does not own.
     */
    @Column(name = "mechanic_ids", columnDefinition = "text")
    private String mechanicIds;

    /** Promised-back time. Always null today — the owner has no such field yet (#1658). */
    @Column(name = "promised_at")
    private Instant promisedAt;

    /** The day the work is scheduled for; scopes the "as of" unit roster. */
    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * When work actually began on this workorder (owner's {@code work_started_at}); null while it
     * has not started. The <em>actual</em> start, never a planned or scheduled time (#2021).
     */
    @Column(name = "work_started_at")
    private Instant workStartedAt;

    /**
     * When this workorder actually completed (owner's {@code completed_at}); null while still
     * open (#2021).
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * The owner's projection of when a running job will finish (#2021). Column kept for contract
     * stability even though the owner publishes {@code null} in every fact today by design —
     * projecting a finish needs estimated remaining labour, which is ADR-0058/ADR-0059 territory
     * and both are still PROPOSED, not accepted. Not populated by this module from any other
     * source: a guessed projection would be indistinguishable from a known one.
     */
    @Column(name = "expected_end_at")
    private Instant expectedEndAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
