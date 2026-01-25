package com.positivity.accounting.dto;

import java.util.Map;

/**
 * Standard error response format for the accounting domain.
 * 
 * Includes error code, message, and optional field-level validation errors.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Error Response</a>
 */
public class ErrorResponse {

    private String errorCode;
    private String message;
    private String correlationId;
    private Long timestamp;
    private Map<String, String> fieldErrors;

    // Constructors
    public ErrorResponse() {
    }

    public ErrorResponse(String errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
    }

    public ErrorResponse(String errorCode, String message, Map<String, String> fieldErrors) {
        this.errorCode = errorCode;
        this.message = message;
        this.fieldErrors = fieldErrors;
    }

    public ErrorResponse(String errorCode, String message, Map<String, String> fieldErrors, String correlationId,
            Long timestamp) {
        this.errorCode = errorCode;
        this.message = message;
        this.fieldErrors = fieldErrors;
        this.correlationId = correlationId;
        this.timestamp = timestamp;
    }

    // Getters and Setters
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

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
