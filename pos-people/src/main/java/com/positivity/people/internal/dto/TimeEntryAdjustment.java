package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AdjustmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Time entry adjustment representing a proposed or approved change to a time entry")
public class TimeEntryAdjustment {
    @Schema(description = "Unique identifier for the adjustment", example = "123e4567-e89b-12d3-a456-426614174000", accessMode = Schema.AccessMode.READ_ONLY)
    private UUID adjustmentId;
    
    @Schema(description = "Time entry identifier being adjusted", example = "123e4567-e89b-12d3-a456-426614174000", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID timeEntryId;
    
    @Schema(description = "Reason code for the adjustment", example = "MISSED_BREAK", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reasonCode;
    
    @Schema(description = "Additional notes explaining the adjustment", example = "Employee forgot to log lunch break")
    private String notes;
    
    @Schema(description = "Proposed new start timestamp", example = "2026-02-17T08:00:00Z")
    private Instant proposedStartAt;
    
    @Schema(description = "Proposed new end timestamp", example = "2026-02-17T17:00:00Z")
    private Instant proposedEndAt;
    
    @Schema(description = "Adjustment in minutes (positive to add time, negative to subtract)", example = "-30")
    private Integer minutesDelta;
    
    @Schema(description = "Current status of the adjustment", example = "PENDING", requiredMode = Schema.RequiredMode.REQUIRED)
    private AdjustmentStatus status;
    
    @Schema(description = "User who created the adjustment request", example = "employee-456")
    private String createdBy;
    
    @Schema(description = "Timestamp when the adjustment was created", example = "2026-02-17T08:30:00Z")
    private Instant createdAt;
    
    @Schema(description = "User who approved or rejected the adjustment", example = "manager-123")
    private String decidedBy;
    
    @Schema(description = "Timestamp when the adjustment was approved or rejected", example = "2026-02-17T09:15:00Z")
    private Instant decidedAt;

    public UUID getAdjustmentId() {
        return adjustmentId;
    }

    public void setAdjustmentId(UUID adjustmentId) {
        this.adjustmentId = adjustmentId;
    }

    public UUID getTimeEntryId() {
        return timeEntryId;
    }

    public void setTimeEntryId(UUID timeEntryId) {
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
