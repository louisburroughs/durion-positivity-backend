package com.positivity.people.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "time_entry_audit")
public class TimeEntryAudit {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "audit_id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID auditId;

    @Column(name = "time_entry_id", nullable = false)
    private String timeEntryId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "actor_id")
    private String actorId;

    @NonNull
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void generateIdAndTimestamp() {
        if (timestamp == null) {
            timestamp = Instant.now(Clock.systemUTC());
        }
    }
}
