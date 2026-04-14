package com.positivity.shopmanager.internal.exception;

/**
 * Thrown when source document (Estimate or Work Order) fails eligibility
 * validation.
 * Mapped to HTTP 422 (Unprocessable Entity) response.
 */
public class SourceNotEligibleException extends RuntimeException {
    private final String errorCode;
    private final String sourceType;
    private final String sourceId;
    private final String sourceStatus;

    public SourceNotEligibleException(
            String errorCode, String message, String sourceType, String sourceId, String sourceStatus) {
        super(message);
        this.errorCode = errorCode;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.sourceStatus = sourceStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSourceStatus() {
        return sourceStatus;
    }
}
