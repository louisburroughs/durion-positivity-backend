package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only vehicle-registry replica fed by {@code vehicle.events.v1} (ADR-0044 §6, #924).
 *
 * <p>pos-vehicle-inventory owns these facts; nothing in this module may write the table except the
 * {@code VehicleEventsListener} consumer. Serves the claim-intake vehicle snapshot (VIN + odometer)
 * previously answered by the retired synchronous {@code VehicleInventoryClient.getVehicle}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_vehicle")
public class ExtVehicleReplica {

    @Id
    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    @Column(name = "vin", length = 64)
    private String vin;

    @Column(name = "odometer_value")
    private Integer odometerValue;

    @Column(name = "odometer_unit", length = 16)
    private String odometerUnit;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hooks for the ArchUnit UUIDv7 rules (ADR-0013): the PK IS a UUIDv7 minted
     * by the owning module and stored verbatim, so the replica generates no identifier of its own.
     * References both {@link UUIDv7Id} (module ArchitectureTest) and {@link UUIDv7Generator}
     * (cross-module EntityStandards) so both identifier-standard rules recognise the replica.
     */
    @Transient
    public Class<?>[] uuidv7Dependency() {
        return new Class<?>[] {UUIDv7Id.class, UUIDv7Generator.class};
    }
}
