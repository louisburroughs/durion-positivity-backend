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
 * Read-only vehicle replica (ADR-0044 §6, parity story I2 replica redesign): fed by
 * {@code vehicle.events.v1}; {@code accountId} is the owning customer party, enabling
 * vehicle-ownership validation without synchronous CRM calls.
 */
@Entity
@Table(name = "ext_vehicle")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtVehicle {

    @Id
    @Column(name = "vehicle_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID vehicleId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID accountId;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private long aggregateVersion;

    @Column(nullable = false)
    private Instant syncedAt;

    /** ArchUnit UUIDv7 rule hook: vehicleId is a UUIDv7 issued by the vehicle registry. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
