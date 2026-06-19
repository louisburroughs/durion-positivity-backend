package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Request to create a time entry adjustment")
public class TimeEntryAdjustmentRequest {

    @NotNull(message = "timeEntryId is required")
    @Schema(
            description = "Time entry identifier to adjust",
            example = "123e4567-e89b-12d3-a456-426614174000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID timeEntryId;

    @NotBlank(message = "reasonCode is required")
    @Schema(
            description = "Reason code for the adjustment",
            example = "MISSED_BREAK",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String reasonCode;

    @Schema(description = "Additional notes explaining the adjustment", example = "Employee forgot to log lunch break", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String notes;

    @Schema(description = "Proposed new start timestamp", example = "2026-02-17T08:00:00-05:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private OffsetDateTime proposedStartAt;

    @Schema(description = "Proposed new end timestamp", example = "2026-02-17T17:00:00-05:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private OffsetDateTime proposedEndAt;

    @Schema(description = "Adjustment in minutes (positive to add time, negative to subtract)", example = "-30", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Integer minutesDelta;

    @Schema(description = "User creating the adjustment request", example = "employee-456", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String createdBy;

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

    public OffsetDateTime getProposedStartAt() {
        return proposedStartAt;
    }

    public void setProposedStartAt(OffsetDateTime proposedStartAt) {
        this.proposedStartAt = proposedStartAt;
    }

    public OffsetDateTime getProposedEndAt() {
        return proposedEndAt;
    }

    public void setProposedEndAt(OffsetDateTime proposedEndAt) {
        this.proposedEndAt = proposedEndAt;
    }

    public Integer getMinutesDelta() {
        return minutesDelta;
    }

    public void setMinutesDelta(Integer minutesDelta) {
        this.minutesDelta = minutesDelta;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
