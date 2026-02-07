package com.positivity.vehicle.internal.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Vehicle record entity for CAP:091 - Vehicle Registry.
 * Supports VIN normalization, global uniqueness, and vehicle-account
 * association.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "vehicle_records", indexes = {
        @Index(name = "idx_vr_account_id", columnList = "account_id"),
        @Index(name = "idx_vr_vin_normalized", columnList = "vin_normalized"),
        @Index(name = "idx_vr_unit_number", columnList = "unit_number"),
        @Index(name = "idx_vr_license_plate", columnList = "license_plate")
})
@EntityListeners(AuditingEntityListener.class)
public class VehicleRecord {

    @Id
    @Column(name = "vehicle_id", updatable = false, nullable = false)
    private UUID vehicleId;

    @PrePersist
    public void generateId() {
        if (vehicleId == null) {
            vehicleId = UUIDv7Generator.generate();
        }
    }

    @NonNull
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @NonNull
    @Column(name = "vin", nullable = false, length = 17)
    private String vin;

    @NonNull
    @Column(name = "vin_normalized", nullable = false, length = 17, unique = true)
    private String vinNormalized;

    @NonNull
    @Column(name = "unit_number", nullable = false)
    private String unitNumber;

    @NonNull
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "license_plate", length = 50)
    private String licensePlate;

    @Column(name = "license_plate_jurisdiction", length = 50)
    private String licensePlateJurisdiction;

    @Column(name = "year")
    private Integer year;

    @Column(name = "make")
    private String make;

    @Column(name = "model")
    private String model;

    @Column(name = "trim")
    private String trim;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "odometer", columnDefinition = "jsonb")
    private OdometerReading odometer;

    @Column(name = "last_service_date")
    private Instant lastServiceDate;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OdometerReading {
        private Long value;
        private String unit; // MILES or KILOMETERS
        private Instant asOfDateTime;
    }
}
