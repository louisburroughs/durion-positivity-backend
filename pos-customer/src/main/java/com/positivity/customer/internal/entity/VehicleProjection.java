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
 * Local projection of vehicle attributes from inbound workorder events.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "vehicle_projection")
public class VehicleProjection {
    @Id
    @Column(name = "vehicle_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID vehicleId;

    @Column(name = "vin", length = 17)
    private String vin;

    @Column(name = "make")
    private String make;

    @Column(name = "model")
    private String model;

    @Column(name = "vehicle_year")
    private Integer year;

    @Column(name = "color")
    private String color;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    /**
     * Explicit dependency hook for ArchUnit UUIDv7 rule.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
