package com.positivity.vehicle.internal.entity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Generator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

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
