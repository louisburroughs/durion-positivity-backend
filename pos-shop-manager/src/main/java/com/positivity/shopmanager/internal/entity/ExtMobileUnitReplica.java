package com.positivity.shopmanager.internal.entity;

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
 * Read-only mobile-service-unit replica fed by {@code location.events.v1} (ADR-0044 §6, #1658).
 *
 * <p>pos-location owns the mobile-unit aggregate; only {@link
 * com.positivity.shopmanager.internal.service.LocationEventsListener} writes this table (R3).
 * Scoped by {@code base_location_id} — the site a unit is dispatched from — so the dashboard's
 * unit roster can include a van that holds no work today, a set no query over workorders can
 * produce.
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

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
