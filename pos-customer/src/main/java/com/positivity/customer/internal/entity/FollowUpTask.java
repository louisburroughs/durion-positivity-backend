package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A piece of customer follow-up someone owes (Story #1153).
 *
 * <p>Declined work and service-due reminders currently have no home: they exist as a note on a
 * closed workorder that nobody revisits. This gives them a queue with an owner and a due date.
 *
 * <p>Completing a task can hand off to booking in pos-shop-manager, but CRM never books —
 * {@code bookedWorkorderId}/{@code bookedAppointmentId} record what the hand-off produced so
 * the follow-up's value is measurable, without this module owning scheduling.
 *
 * <p>{@code sourceEventId} is populated only by the event-fed path (FI-3, #1133) and carries a
 * unique index, so a replayed declined-service fact yields exactly one task.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "follow_up_task",
        indexes = {
            @Index(name = "idx_follow_up_task_party", columnList = "party_id, status"),
            @Index(name = "idx_follow_up_task_queue", columnList = "status, due_date"),
            @Index(name = "idx_follow_up_task_assignee", columnList = "assigned_to, status")
        })
public class FollowUpTask {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "task_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false)
    private UUID partyId;

    /** Vehicle the follow-up concerns, when it is vehicle-specific. */
    @Column(name = "vehicle_id", columnDefinition = "UUID")
    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", columnDefinition = "VARCHAR(40)", nullable = false)
    private FollowUpType type;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** CSR who owns the task; null means unassigned and sitting in the shared queue. */
    @Column(name = "assigned_to", length = 255)
    private String assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "VARCHAR(20)", nullable = false)
    @Builder.Default
    private FollowUpStatus status = FollowUpStatus.OPEN;

    @Column(name = "source_workorder_id", length = 64)
    private String sourceWorkorderId;

    /** Originating event envelope id for event-fed tasks; the ingestion idempotency key. */
    @Column(name = "source_event_id", length = 64)
    private String sourceEventId;

    /** Why the follow-up is needed, e.g. the declined service line's description. */
    @Column(name = "reason", length = 500)
    private String reason;

    /** What happened when it was worked. Recorded on completion or dismissal. */
    @Column(name = "outcome", length = 500)
    private String outcome;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "booked_workorder_id", columnDefinition = "UUID")
    private UUID bookedWorkorderId;

    @Column(name = "booked_appointment_id", columnDefinition = "UUID")
    private UUID bookedAppointmentId;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 255)
    private String closedBy;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
