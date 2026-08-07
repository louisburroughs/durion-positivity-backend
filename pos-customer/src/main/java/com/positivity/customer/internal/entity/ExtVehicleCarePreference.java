package com.positivity.customer.internal.entity;

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
 * Read-only per-vehicle care-preference replica owned by pos-vehicle-inventory, populated
 * exclusively by the {@code vehicle.care-preference.updated} consumer on {@code vehicle.events.v1}
 * (#1175). Nothing else may write it.
 *
 * <p>{@code serviceIntervalMonths} is nullable: null means "no per-vehicle override" and CRM
 * service-due consumers fall back to the module-wide {@code pos.customer.crm.service-due-months}
 * default. A care-preference delete lands as a versioned tombstone (interval null,
 * {@code aggregateVersion} advanced) rather than a hard delete, so a replayed older fact can never
 * resurrect a removed override.
 *
 * <p>{@code aggregateVersion} is the owner's last-writer-wins epoch-millis stamp — care
 * preferences are hard-deleted and re-created upstream, so it is not a JPA row version and the
 * consumer's stale guard uses {@code >} (the people-contact replica precedent), not {@code >=}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_vehicle_care_preference")
public class ExtVehicleCarePreference {

    @Id
    @Column(name = "vehicle_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID vehicleId;

    @Column(name = "service_interval_months")
    private Integer serviceIntervalMonths;

    @Column(name = "preference_updated_at")
    private Instant preferenceUpdatedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for ArchUnit UUIDv7 rule.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
