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
 * Vehicle identity as pos-vehicle-inventory last published it (#1346, ADR-0044 R3).
 *
 * <h2>Why this domain needs it</h2>
 *
 * A fleet program identifies a vehicle by plate, VIN or its own unit number. This domain holds only
 * a {@code vehicleId} UUID, so without these fields an authorization request cannot name the vehicle
 * it is about — and CAP-323's canonical request refuses to be built without at least one of them.
 *
 * <h2>Read from facts, not asked for</h2>
 *
 * pos-vehicle-inventory is a Domain module under ADR-0044, so it is read through
 * {@code vehicle.events.v1} rather than called. pos-warranty already does the same thing with the
 * same fact, which is the precedent this follows.
 *
 * <h2>Not a second vehicle registry</h2>
 *
 * Only the identifying fields are here. Nothing is authoritative, nothing is written back, and
 * anything not needed to name a vehicle to a fleet program is deliberately absent — a replica that
 * grows toward completeness becomes a competing source of truth, and then the two disagree.
 */
@Entity
@Table(name = "ext_vehicle")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtVehicleReplica {

    @Id
    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    @Column(name = "vin", length = 64)
    private String vin;

    @Column(name = "license_plate", length = 32)
    private String licensePlate;

    /** The fleet's own unit number for the vehicle, where its operator uses one. */
    @Column(name = "unit_number", length = 64)
    private String unitNumber;

    @Column(name = "odometer_value")
    private Integer odometerValue;

    @Column(name = "odometer_unit", length = 16)
    private String odometerUnit;

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
