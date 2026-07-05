package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.LocationSyncLogScope;
import com.positivity.inventory.internal.enums.LocationSyncOutcome;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable audit entry for the location sync flow (CAP-214 #40).
 * A {@code RUN}-scoped row summarizes one sync run; {@code RECORD}-scoped
 * rows capture individual roster records that could not be applied.
 */
@Entity
@Table(name = "location_sync_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class LocationSyncLogEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "sync_log_id", updatable = false, nullable = false)
    private UUID syncLogId;

    @Column(name = "sync_run_id", nullable = false, updatable = false)
    private UUID syncRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private LocationSyncLogScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private LocationSyncOutcome outcome;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "hr_location_id")
    private String hrLocationId;

    @Column(name = "payload", length = 4000)
    private String payload;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "locations_processed")
    private Integer locationsProcessed;

    @Column(name = "locations_created")
    private Integer locationsCreated;

    @Column(name = "locations_updated")
    private Integer locationsUpdated;

    @Column(name = "locations_unchanged")
    private Integer locationsUnchanged;

    @Column(name = "locations_failed")
    private Integer locationsFailed;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
