package com.positivity.vehicle.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.vehicle.internal.enums.OdometerUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.GeneratedValue;
import com.positivity.shared.id.UUIDv7Id;
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
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "vehicle_id", updatable = false, nullable = false)
    private UUID vehicleId;
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

    @Column(name = "model_year")
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OdometerReading {
        @JsonProperty("value")
        private Long value;

        @JsonProperty("unit")
        private OdometerUnit unit;

        @JsonProperty("asOfDateTime")
        private Instant asOfDateTime;

        @JsonCreator
        public OdometerReading(
                @JsonProperty("value") Long value,
                @JsonProperty("unit") OdometerUnit unit,
                @JsonProperty("asOfDateTime") Instant asOfDateTime) {
            this.value = value;
            this.unit = unit;
            this.asOfDateTime = asOfDateTime;
        }
    }
}
