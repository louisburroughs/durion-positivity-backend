package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable reschedule history record for an appointment.
 *
 * <p>
 * CAP-249 Story #11: stores each reschedule event for audit trail compliance.
 * Max 2 reschedules without manager approval is a pending business rule
 * (see TODO in AppointmentsServiceImpl#rescheduleAppointment).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "reschedule_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RescheduleHistory extends TenantScopedEntity {

    @Id
    @UUIDv7Id
    @GeneratedValue
    @Column(name = "reschedule_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID rescheduleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false, updatable = false)
    private Appointment appointment;

    public UUID getAppointmentId() {
        return appointment != null ? appointment.getAppointmentId() : null;
    }

    @NonNull
    @Column(name = "previous_start_at", nullable = false, updatable = false)
    private Instant previousStartAt;

    @NonNull
    @Column(name = "previous_end_at", nullable = false, updatable = false)
    private Instant previousEndAt;

    @NonNull
    @Column(name = "new_start_at", nullable = false, updatable = false)
    private Instant newStartAt;

    @NonNull
    @Column(name = "new_end_at", nullable = false, updatable = false)
    private Instant newEndAt;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "reschedule_reason", nullable = false, length = 50, updatable = false)
    private RescheduleReasonCode rescheduleReason;

    @Column(name = "reschedule_reason_notes", length = 1000, updatable = false)
    private String rescheduleReasonNotes;

    @NonNull
    @Column(name = "rescheduled_by", nullable = false, updatable = false)
    private String rescheduledBy;

    @NonNull
    @Column(name = "rescheduled_at", nullable = false, updatable = false)
    private Instant rescheduledAt;

    @Column(name = "assignment_status", length = 50, updatable = false)
    private String assignmentStatus;

    @Column(name = "notify_customer", nullable = false, updatable = false)
    private boolean notifyCustomer;

    @Column(name = "notification_status", length = 50)
    private String notificationStatus;

    /**
     * DECISION-SHOPMGMT-004: whether this reschedule counts against the 2-free-reschedules
     * allowance. {@code false} only for a shop-caused reschedule — reason {@code EQUIPMENT_ISSUE},
     * or the appointment was already DECISION-SHOPMGMT-022 "affected" at the moment of this
     * reschedule, evaluated before any change — never for an ordinary customer-caused one.
     */
    @Column(name = "counts_against_allowance", nullable = false, updatable = false)
    private boolean countsAgainstAllowance;

    /**
     * The appointment's resource before this reschedule (DECISION-SHOPMGMT-022 rule 3), in the same
     * shape as {@code appointment.resource_id} (a bay/mobile-unit id, or the legacy technician
     * reading); {@code null} when it had none (UNASSIGNED).
     */
    @Column(name = "previous_resource_id", length = 128, updatable = false)
    private String previousResourceId;

    /**
     * The resource this reschedule moved the appointment onto, when the caller named a {@code
     * newResourceId}/{@code newResourceType}; {@code null} when this reschedule did not touch the
     * resource axis.
     */
    @Column(name = "new_resource_id", length = 128, updatable = false)
    private String newResourceId;

    /**
     * The manager's reason for approving a reschedule beyond the free allowance
     * (DECISION-SHOPMGMT-004, {@code appointments:reschedule:approve}); {@code null} for every
     * reschedule that needed no approval.
     */
    @Column(name = "approval_reason", length = 1000, updatable = false)
    private String approvalReason;

    @NonNull
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreatedDate
    private Instant createdAt;
}
