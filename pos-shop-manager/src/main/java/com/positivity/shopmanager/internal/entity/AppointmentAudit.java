package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.enums.AppointmentAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
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
    @Column(name = "audit_id", columnDefinition = "UUID")
    private UUID auditId;

    @Column(name = "appointment_id", nullable = false, columnDefinition = "UUID")
    private UUID appointmentId;

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

    @PrePersist
    public void generateAuditId() {
        if (auditId == null) {
            auditId = UUIDv7Generator.generate();
        }
    }
}
