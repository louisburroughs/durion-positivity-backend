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
 * Read-only replica of the vehicle registry owned by pos-vehicle-inventory, populated exclusively
 * by the {@code vehicle.events.v1} consumer (ADR-0044 R3, #843). Nothing else may write it.
 *
 * <p>{@code aggregateVersion} mirrors the owner's JPA optimistic-lock version and guards against
 * out-of-order event application.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_vehicle")
public class ExtVehicle {

    @Id
    @Column(name = "vehicle_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID vehicleId;

    @Column(name = "account_id", columnDefinition = "UUID", nullable = false)
    private UUID accountId;

    @Column(name = "vin", nullable = false, length = 17)
    private String vin;

    @Column(name = "vin_normalized", nullable = false, length = 17)
    private String vinNormalized;

    @Column(name = "unit_number")
    private String unitNumber;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "license_plate", length = 50)
    private String licensePlate;

    @Column(name = "license_plate_jurisdiction", length = 50)
    private String licensePlateJurisdiction;

    @Column(name = "model_year")
    private Integer year;

    @Column(name = "make")
    private String make;

    @Column(name = "model")
    private String model;

    @Column(name = "trim")
    private String trim;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "vehicle_created_at")
    private Instant vehicleCreatedAt;

    @Column(name = "vehicle_updated_at")
    private Instant vehicleUpdatedAt;

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
