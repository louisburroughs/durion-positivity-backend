package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Conflict response DTO for appointment scheduling conflicts (HTTP 409).
 * Includes list of detected conflicts and optional suggested alternatives.
 * Follows error envelope pattern with errorCode, message, correlationId,
 * timestamp.
 */
@Schema(description = "Error envelope returned when an appointment scheduling conflict is detected (HTTP 409)")
public class ConflictResponse {

    @Schema(description = "Machine-readable error code", example = "SCHEDULING_CONFLICT", requiredMode = REQUIRED)
    private String errorCode; // Always "SCHEDULING_CONFLICT"

    @Schema(
            description = "User-friendly conflict summary",
            example = "The selected time conflicts with an existing booking",
            requiredMode = REQUIRED)
    private String message; // User-friendly conflict summary

    @Schema(
            description = "Correlation/trace identifier for the request",
            example = "01960003-0000-7000-8000-0000000000ff",
            requiredMode = REQUIRED)
    private String correlationId; // Request trace ID

    @Schema(
            description = "Timestamp the error was generated in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    private Instant timestamp; // Error timestamp

    @Schema(description = "List of specific detected conflicts", requiredMode = REQUIRED)
    private List<Conflict> conflicts; // Array of specific conflicts

    @Schema(description = "Optional suggested alternative slots (may be null or empty)", requiredMode = NOT_REQUIRED)
    private List<SuggestedAlternative> suggestedAlternatives; // Optional alternatives (may be null/empty)

    public ConflictResponse() {}

    public ConflictResponse(
            String errorCode, String message, String correlationId, Instant timestamp, List<Conflict> conflicts) {
        this.errorCode = errorCode;
        this.message = message;
        this.correlationId = correlationId;
        this.timestamp = timestamp;
        this.conflicts = conflicts;
    }

    public ConflictResponse(
            String errorCode,
            String message,
            String correlationId,
            Instant timestamp,
            List<Conflict> conflicts,
            List<SuggestedAlternative> suggestedAlternatives) {
        this.errorCode = errorCode;
        this.message = message;
        this.correlationId = correlationId;
        this.timestamp = timestamp;
        this.conflicts = conflicts;
        this.suggestedAlternatives = suggestedAlternatives;
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

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public List<Conflict> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<Conflict> conflicts) {
        this.conflicts = conflicts;
    }

    public List<SuggestedAlternative> getSuggestedAlternatives() {
        return suggestedAlternatives;
    }

    public void setSuggestedAlternatives(List<SuggestedAlternative> suggestedAlternatives) {
        this.suggestedAlternatives = suggestedAlternatives;
    }

    /**
     * Represents a single scheduling conflict.
     * Severity determines overridability: HARD conflicts cannot be overridden;
     * SOFT conflicts can be overridden with permission and reason.
     */
    @Schema(description = "A single detected scheduling conflict")
    public static class Conflict {

        @Schema(description = "Conflict severity (HARD or SOFT)", example = "SOFT", requiredMode = REQUIRED)
        private String severity; // HARD | SOFT (enum)

        @Schema(
                description = "Machine-readable conflict code",
                example = "MECHANIC_UNAVAILABLE",
                requiredMode = REQUIRED)
        private String code; // e.g., MECHANIC_UNAVAILABLE, OUTSIDE_OPERATING_HOURS

        @Schema(
                description = "User-safe description of the conflict",
                example = "The selected mechanic is already booked",
                requiredMode = REQUIRED)
        private String message; // User-safe description

        @Schema(
                description = "Whether the conflict can be overridden (HARD=false, SOFT=true)",
                example = "true",
                requiredMode = REQUIRED)
        private Boolean overridable; // HARD=false, SOFT=true

        @Schema(
                description = "Resource affected by the conflict",
                example = "Mechanic: John Doe",
                requiredMode = NOT_REQUIRED)
        private String affectedResource; // e.g., "Mechanic: John Doe", "Facility hours: 8am-5pm"

        public Conflict() {}

        public Conflict(String severity, String code, String message, Boolean overridable, String affectedResource) {
            this.severity = severity;
            this.code = code;
            this.message = message;
            this.overridable = overridable;
            this.affectedResource = affectedResource;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Boolean getOverridable() {
            return overridable;
        }

        public void setOverridable(Boolean overridable) {
            this.overridable = overridable;
        }

        public String getAffectedResource() {
            return affectedResource;
        }

        public void setAffectedResource(String affectedResource) {
            this.affectedResource = affectedResource;
        }
    }

    /**
     * Suggests an alternative appointment slot that avoids detected conflicts.
     * Provided as a convenience; client can ignore and choose own time.
     */
    @Schema(description = "A suggested alternative appointment slot that avoids detected conflicts")
    public static class SuggestedAlternative {

        @Schema(
                description = "Suggested start time (ISO-8601 with offset)",
                example = "2026-06-18T11:00:00-04:00",
                requiredMode = REQUIRED)
        private String startDateTime; // ISO-8601 with offset

        @Schema(
                description = "Suggested end time (ISO-8601 with offset)",
                example = "2026-06-18T13:00:00-04:00",
                requiredMode = REQUIRED)
        private String endDateTime; // ISO-8601 with offset

        @Schema(
                description = "Reason this alternative is suggested",
                example = "Mechanic available",
                requiredMode = NOT_REQUIRED)
        private String reason; // e.g., "Mechanic available"

        public SuggestedAlternative() {}

        public SuggestedAlternative(String startDateTime, String endDateTime, String reason) {
            this.startDateTime = startDateTime;
            this.endDateTime = endDateTime;
            this.reason = reason;
        }

        public String getStartDateTime() {
            return startDateTime;
        }

        public void setStartDateTime(String startDateTime) {
            this.startDateTime = startDateTime;
        }

        public String getEndDateTime() {
            return endDateTime;
        }

        public void setEndDateTime(String endDateTime) {
            this.endDateTime = endDateTime;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
