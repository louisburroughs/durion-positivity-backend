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
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event processing log for CAP:091 Story #101.
 * Tracks VehicleUpdated event processing with idempotency and conflict
 * tracking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "event_processing_log", indexes = {
        @Index(name = "idx_epl_event_id", columnList = "event_id"),
        @Index(name = "idx_epl_workorder_id", columnList = "workorder_id"),
        @Index(name = "idx_epl_vehicle_id", columnList = "vehicle_id"),
        @Index(name = "idx_epl_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
public class EventProcessingLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "log_id", updatable = false, nullable = false)
    private UUID logId;

    @NonNull
    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @NonNull
    @Column(name = "workorder_id", nullable = false)
    private String workorderId;

    @NonNull
    @Column(name = "vehicle_id", nullable = false)
    private String vehicleId;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ProcessingStatus status;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_policy", length = 50)
    private ConflictPolicy conflictPolicy;

    @Nullable
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Nullable
    @Column(name = "processed_at")
    private Instant processedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum ProcessingStatus {
        SUCCESS,
        PENDING_REVIEW,
        DUPLICATE_EVENT,
        CONFLICT_VIN_CHANGE,
        ERROR_VEHICLE_NOT_FOUND,
        ERROR_INVALID_ODOMETER,
        ERROR_PROCESSING
    }

    public enum ConflictPolicy {
        MILEAGE_DECREASE, // Accept and flag for review
        VIN_CHANGE, // Reject (controlled separately)
        DUPLICATE_EVENT // Skip
    }
}
