package com.positivity.people.internal.entity;

import java.time.Clock;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;
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

    // getters/setters
    public UUID getAuditId() {
        return auditId;
    }

    public void setAuditId(UUID auditId) {
        this.auditId = auditId;
    }

    public String getTimeEntryId() {
        return timeEntryId;
    }

    public void setTimeEntryId(String timeEntryId) {
        this.timeEntryId = timeEntryId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
}

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
}
}
