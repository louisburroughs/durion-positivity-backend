package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Response from creating or updating a time entry adjustment")
public class TimeEntryAdjustmentResponse {

    @Schema(
            description = "Unique identifier of the created or updated adjustment",
            example = "123e4567-e89b-12d3-a456-426614174000")
    private UUID adjustmentId;

    @Schema(
            description = "Indicates whether the operation was successful",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean success;

    @Schema(
            description = "Human-readable message describing the outcome",
            example = "Adjustment created and pending approval")
    private String message;

    public TimeEntryAdjustmentResponse() {}

    public TimeEntryAdjustmentResponse(UUID adjustmentId, boolean success, String message) {
        this.adjustmentId = adjustmentId;
        this.success = success;
        this.message = message;
    }

    public UUID getAdjustmentId() {
        return adjustmentId;
    }

    public void setAdjustmentId(UUID adjustmentId) {
        this.adjustmentId = adjustmentId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
