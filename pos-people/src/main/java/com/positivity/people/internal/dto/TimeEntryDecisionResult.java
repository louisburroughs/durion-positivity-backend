package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Outcome of an approve/reject decision for a single time entry")
public class TimeEntryDecisionResult {

    @Schema(
            description = "Time entry identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String timeEntryId;

    @Schema(
            description = "Whether the decision was applied successfully",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean success;

    @Schema(
            description = "Error code when the decision failed",
            example = "ENTRY_NOT_FOUND",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String errorCode;

    @Schema(
            description = "Human-readable message describing the outcome",
            example = "Time entry approved",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String message;

    public TimeEntryDecisionResult() {}

    public TimeEntryDecisionResult(String timeEntryId, boolean success, String errorCode, String message) {
        this.timeEntryId = timeEntryId;
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
    }

    public String getTimeEntryId() {
        return timeEntryId;
    }

    public void setTimeEntryId(String timeEntryId) {
        this.timeEntryId = timeEntryId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
