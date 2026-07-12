package com.positivity.peoplecontact.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

@Schema(description = "Machine-readable error payload returned on failed requests")
public class ErrorResponse {

    @Schema(
            description = "Stable machine-readable error code",
            example = "VALIDATION_FAILED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String errorCode;

    @Schema(
            description = "Human-readable error message",
            example = "Request validation failed",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(
            description = "Correlation identifier echoed from the request",
            example = "01960011-0000-7000-8000-0000000000aa",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String correlationId;

    @Schema(
            description = "Timestamp the error occurred",
            example = "2026-02-16T17:00:00Z",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant timestamp;

    @Schema(
            description = "Per-field validation errors keyed by field name",
            example = "{\"employeeNumber\":\"must not be blank\"}",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Map<String, String> fieldErrors;

    public ErrorResponse() {}

    public ErrorResponse(String errorCode, String message, String correlationId, Instant timestamp) {
        this.errorCode = errorCode;
        this.message = message;
        this.correlationId = correlationId;
        this.timestamp = timestamp;
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

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }
}
