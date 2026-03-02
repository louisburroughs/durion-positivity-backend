package com.positivity.shopmanager.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.shopmanager.internal.enums.RescheduleReasonCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable reschedule history record for an appointment.
 *
 * <p>
 * CAP-249 Story #11: stores each reschedule event for audit trail compliance.
 * Max 2 reschedules without manager approval is a pending business rule
 * (see TODO in AppointmentsServiceImpl#rescheduleAppointment).
 */
@Entity
@Table(name = "reschedule_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RescheduleHistory {

    @Id
    @UUIDv7Id
    @GeneratedValue
    @Column(name = "reschedule_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID rescheduleId;

    @NonNull
    @Column(name = "appointment_id", nullable = false, updatable = false)
    private UUID appointmentId;

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

    @Column(name = "conflict_overridden", nullable = false, updatable = false)
    private boolean conflictOverridden;

    @Column(name = "assignment_status", length = 50, updatable = false)
    private String assignmentStatus;

    @Column(name = "notify_customer", nullable = false, updatable = false)
    private boolean notifyCustomer;

    @Column(name = "notification_status", length = 50)
    private String notificationStatus;

    @NonNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
