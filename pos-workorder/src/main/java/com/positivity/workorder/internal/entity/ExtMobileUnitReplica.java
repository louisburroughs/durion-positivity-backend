package com.positivity.workorder.internal.entity;

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
 * Read-only mobile-unit replica fed by {@code location.events.v1} (ADR-0044 §6, #1656).
 *
 * <p>pos-location owns the mobile-unit aggregate — a field-service vehicle with its own identity
 * and lifecycle, sharing no table with {@code BayEntity}. Nothing in this module may write here
 * except the event consumer.
 *
 * <p>{@code baseLocationId} mirrors the owner's {@code base_location_id}: it is what scopes a unit
 * to one shop's dispatch board, the mobile-unit counterpart of {@link ExtBayReplica#getLocationId()}.
 * A unit is dispatched from its base site even though the work happens in the field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_mobile_unit")
public class ExtMobileUnitReplica {

    @Id
    @Column(name = "mobile_unit_id", nullable = false)
    private UUID mobileUnitId;

    /** The site this unit is based at — the dashboard scopes its mobile-unit panel by this column. */
    @Column(name = "base_location_id")
    private UUID baseLocationId;

    @Column(name = "name")
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module's envelope factory; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
