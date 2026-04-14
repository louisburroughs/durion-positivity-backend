package com.positivity.shopmanager.internal.dto;

import java.time.Instant;
import java.util.List;

/**
 * Conflict response DTO for appointment scheduling conflicts (HTTP 409).
 * Includes list of detected conflicts and optional suggested alternatives.
 * Follows error envelope pattern with errorCode, message, correlationId,
 * timestamp.
 */
public class ConflictResponse {
    private String errorCode; // Always "SCHEDULING_CONFLICT"
    private String message; // User-friendly conflict summary
    private String correlationId; // Request trace ID
    private Instant timestamp; // Error timestamp
    private List<Conflict> conflicts; // Array of specific conflicts
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
    public static class Conflict {
        private String severity; // HARD | SOFT (enum)
        private String code; // e.g., MECHANIC_UNAVAILABLE, OUTSIDE_OPERATING_HOURS
        private String message; // User-safe description
        private Boolean overridable; // HARD=false, SOFT=true
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
    public static class SuggestedAlternative {
        private String startDateTime; // ISO-8601 with offset
        private String endDateTime; // ISO-8601 with offset
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
