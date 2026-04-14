package com.positivity.people.internal.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Batch request payload for making decisions (approve/reject) on multiple time entries")
public class TimeEntryDecisionBatchRequest {

    @Schema(description = "List of decisions for the time entries", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Decisions list must not be null")
    @NotEmpty(message = "Decisions list must not be empty")
    @Valid
    private List<Decision> decisions;

    @Schema(description = "An individual decision for a specific time entry")
    public static class Decision {

        @Schema(description = "The unique identifier of the time entry", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "123e4567-e89b-12d3-a456-426614174000")
        @NotBlank(message = "Time entry ID is required")
        private String timeEntryId;

        @Schema(description = "The reason for rejection. Applicable if this is a rejection request.",
                example = "Incomplete work details")
        private String rejectionReason;

        public Decision() {
        }

        public Decision(String timeEntryId) {
            this.timeEntryId = timeEntryId;
        }

        public String getTimeEntryId() {
            return timeEntryId;
        }

        public void setTimeEntryId(String timeEntryId) {
            this.timeEntryId = timeEntryId;
        }

        public String getRejectionReason() {
            return rejectionReason;
        }

        public void setRejectionReason(String rejectionReason) {
            this.rejectionReason = rejectionReason;
        }

    }

    public TimeEntryDecisionBatchRequest() {
    }

    public TimeEntryDecisionBatchRequest(List<Decision> decisions) {
        this.decisions = decisions;
    }

    public List<Decision> getDecisions() {
        return decisions;
    }

    public void setDecisions(List<Decision> decisions) {
        this.decisions = decisions;
    }

}
