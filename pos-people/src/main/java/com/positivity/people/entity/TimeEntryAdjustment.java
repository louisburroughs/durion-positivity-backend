package com.positivity.people.entity;

import jakarta.persistence.*;
import java.time.Instant;
import com.positivity.people.model.AdjustmentStatus;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.util.UUID;

@Entity
@Table(name = "time_entry_adjustment")
public class TimeEntryAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "adjustment_id", updatable = false, nullable = false)
    private UUID adjustmentId;

    @Column(name = "time_entry_id", nullable = false)
    private String timeEntryId;

    @Column(name = "reason_code", length = 200)
    private String reasonCode;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "proposed_start_at")
    private Instant proposedStartAt;

    @Column(name = "proposed_end_at")
    private Instant proposedEndAt;

    @Column(name = "minutes_delta")
    private Integer minutesDelta;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50)
    private AdjustmentStatus status;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null)
            createdAt = Instant.now();
    }

    // Getters and setters
    public UUID getAdjustmentId() {
        return adjustmentId;
    }

    public void setAdjustmentId(UUID adjustmentId) {
        this.adjustmentId = adjustmentId;
    }

    public String getTimeEntryId() {
        return timeEntryId;
    }

    public void setTimeEntryId(String timeEntryId) {
        this.timeEntryId = timeEntryId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getProposedStartAt() {
        return proposedStartAt;
    }

    public void setProposedStartAt(Instant proposedStartAt) {
        this.proposedStartAt = proposedStartAt;
    }

    public Instant getProposedEndAt() {
        return proposedEndAt;
    }

    public void setProposedEndAt(Instant proposedEndAt) {
        this.proposedEndAt = proposedEndAt;
    }

    public Integer getMinutesDelta() {
        return minutesDelta;
    }

    public void setMinutesDelta(Integer minutesDelta) {
        this.minutesDelta = minutesDelta;
    }

    public AdjustmentStatus getStatus() {
        return status;
    }

    public void setStatus(AdjustmentStatus status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
