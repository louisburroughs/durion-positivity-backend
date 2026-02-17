package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AdjustmentStatus;
import java.time.Instant;
import java.util.UUID;

public class TimeEntryAdjustment {
    private UUID adjustmentId;
    private String timeEntryId;
    private String reasonCode;
    private String notes;
    private Instant proposedStartAt;
    private Instant proposedEndAt;
    private Integer minutesDelta;
    private AdjustmentStatus status;
    private String createdBy;
    private Instant createdAt;
    private String decidedBy;
    private Instant decidedAt;

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
