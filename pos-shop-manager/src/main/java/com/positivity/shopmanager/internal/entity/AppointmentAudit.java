package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.shopmanager.internal.enums.AppointmentAction;
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
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Table(name = "appointment_audit")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentAudit {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "audit_id", columnDefinition = "UUID")
    private UUID auditId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    public UUID getAppointmentId() {
        return appointment != null ? appointment.getAppointmentId() : null;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private AppointmentAction action;

    @Column(name = "actor_id", nullable = false, length = 255)
    private String actorId;

    @Column(name = "previous_start_at")
    private Instant previousStartAt;

    @Column(name = "previous_end_at")
    private Instant previousEndAt;

    @Column(name = "new_start_at")
    private Instant newStartAt;

    @Column(name = "new_end_at")
    private Instant newEndAt;

    @Column(name = "cancellation_reason", length = 128)
    private String cancellationReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
