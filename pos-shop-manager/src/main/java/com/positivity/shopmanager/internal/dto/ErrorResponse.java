package com.positivity.shopmanager.internal.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Standard error response DTO for Shop Manager API endpoints.
 * Includes correlationId for distributed tracing and debugging.
 */
public class ErrorResponse {
    private String code;
    private String message;
    private Integer status;
    private String correlationId;
    private Instant timestamp;
    private Map<String, String> fieldErrors;

    public ErrorResponse() {
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
