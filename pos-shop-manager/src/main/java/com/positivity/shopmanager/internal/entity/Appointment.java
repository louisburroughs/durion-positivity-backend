package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.shopmanager.internal.enums.AppointmentSourceType;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.CancellationReasonCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "appointment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appointment {

    @Id
    @UUIDv7Id
    @GeneratedValue
    @Column(name = "appointment_id", columnDefinition = "UUID")
    private UUID appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AppointmentStatus status;

    @Column(name = "location_id", nullable = false, columnDefinition = "UUID")
    private UUID locationId;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "crm_customer_id", nullable = false, columnDefinition = "UUID")
    private UUID crmCustomerId;

    @Column(name = "crm_vehicle_id", nullable = false, columnDefinition = "UUID")
    private UUID crmVehicleId;

    @Column(name = "customer_snapshot", columnDefinition = "TEXT")
    private String customerSnapshot;

    @Column(name = "vehicle_snapshot", columnDefinition = "TEXT")
    private String vehicleSnapshot;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "workorder_link_ref", length = 255)
    private String workorderLinkRef;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 128)
    private CancellationReasonCode cancellationReasonCode;

    @Column(name = "cancellation_notes", length = 1000)
    private String cancellationNotes;

    /**
     * Set to {@code true} when this appointment was scheduled despite a detected
     * conflict.
     */
    @Builder.Default
    @Column(name = "is_conflict_override", nullable = false)
    private boolean isConflictOverride = false;

    /**
     * Source type identifying the workexec entity (ESTIMATE or WORK_ORDER) from
     * which this appointment was created. Null when booked directly.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 32)
    private AppointmentSourceType sourceType;

    /**
     * External identifier of the originating estimate or work order.
     * Immutable once set; populated together with {@link #sourceType}.
     */
    @Column(name = "source_id", length = 128)
    private String sourceId;

    /**
     * Service-level assignment status. Set to {@code AWAITING_SKILL_FULFILLMENT}
     * when no mechanics with the required skills are available at creation time.
     * Skill-fulfillment enforcement is pending a follow-up story; this field
     * is persisted to support that future implementation.
     */
    @Column(name = "assignment_status", length = 64)
    private String assignmentStatus;

    @Builder.Default
    @Column(name = "reopen_flag", nullable = false)
    private boolean reopenFlag = false;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "appointment_status_timeline",
            joinColumns = @JoinColumn(name = "appointment_id", referencedColumnName = "appointment_id"))
    private List<StatusTimelineEntry> statusTimeline = new ArrayList<>();
}
