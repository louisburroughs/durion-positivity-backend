package com.positivity.people.internal.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "time_entry_audit")
public class TimeEntryAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "audit_id", updatable = false, nullable = false)
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

    @PrePersist
    public void prePersist() {
        if (timestamp == null)
            timestamp = Instant.now();
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
}
