package com.positivity.vehicle.internal.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Vehicle care preferences entity for CAP:091 Story #102.
 * Stores structured and unstructured vehicle care preferences using JSONB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "vehicle_care_preferences", indexes = {
        @Index(name = "idx_vcp_vehicle_id", columnList = "vehicle_id")
})
@EntityListeners(AuditingEntityListener.class)
public class VehicleCarePreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NonNull
    @Column(name = "vehicle_id", nullable = false, unique = true)
    private UUID vehicleId;

    @NonNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> preferences;

    @Column(name = "service_notes", columnDefinition = "TEXT")
    private String serviceNotes;

    @NonNull
    @Column(name = "created_by_user_id", nullable = false)
    private UUID createdByUserId;

    @NonNull
    @Column(name = "updated_by_user_id", nullable = false)
    private UUID updatedByUserId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
