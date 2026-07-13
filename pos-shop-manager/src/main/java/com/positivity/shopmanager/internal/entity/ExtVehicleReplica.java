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
 * Read-only vehicle replica fed by {@code vehicle.events.v1} ({@code vehicle.vehicle.updated},
 * ADR-0044 §6, #891). pos-vehicle-inventory owns the facts; this module only builds appointment
 * vehicle snapshots and validates the vehicle→customer ownership relationship — the surface
 * previously fetched synchronously by {@code CrmVehicleClient} (deferred from Phase 2 #843).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_vehicle")
public class ExtVehicleReplica {

    @Id
    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    /** Owning customer party id (pos-customer party UUID). */
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "vin", nullable = false, length = 17)
    private String vin;

    @Column(name = "unit_number")
    private String unitNumber;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "license_plate", length = 50)
    private String licensePlate;

    @Column(name = "model_year")
    private Integer year;

    @Column(name = "make")
    private String make;

    @Column(name = "model")
    private String model;

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
