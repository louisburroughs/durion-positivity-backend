package com.positivity.people.internal.entity;

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
 * Read-only job-time replica fed by {@code workorder.job_time.recorded.v1} facts
 * (ADR-0044 §6, #875). One row per finalized labor entry of a completed workorder; the
 * attendance-discrepancy report aggregates minutes per technician/location/local-date from
 * {@code endAtUtc} using the caller's timezone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_workorder_job_time")
public class ExtJobTimeReplica {

    @Id
    @Column(name = "labor_entry_id", nullable = false)
    private UUID laborEntryId;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "end_at_utc", nullable = false)
    private Instant endAtUtc;

    @Column(name = "minutes", nullable = false)
    private int minutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by pos-workorder; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
