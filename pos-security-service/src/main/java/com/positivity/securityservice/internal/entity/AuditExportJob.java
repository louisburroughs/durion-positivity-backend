package com.positivity.securityservice.internal.entity;

import com.positivity.securityservice.internal.enums.AuditDeliveryMode;
import com.positivity.securityservice.internal.enums.AuditExportFormat;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One asynchronous audit export (#2408, B-4): the filter scope it was requested with and where it
 * is in its lifecycle, {@code PENDING -> IN_PROGRESS -> COMPLETED | FAILED}. The produced file is
 * kept apart in {@link AuditExportFile} so a status poll never loads the content.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "audit_export_jobs")
public class AuditExportJob extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "job_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AuditExportStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 8, updatable = false)
    private AuditExportFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 16, updatable = false)
    private AuditDeliveryMode deliveryMode;

    @Column(name = "filter_from_date", updatable = false)
    private Instant filterFromDate;

    @Column(name = "filter_to_date", updatable = false)
    private Instant filterToDate;

    @Column(name = "filter_event_type", length = 100, updatable = false)
    private String filterEventType;

    @Column(name = "filter_actor_id", length = 255, updatable = false)
    private String filterActorId;

    @Column(name = "filter_aggregate_id", length = 255, updatable = false)
    private String filterAggregateId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
