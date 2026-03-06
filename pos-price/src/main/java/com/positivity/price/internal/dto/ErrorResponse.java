package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Standard API error response envelope.
 */
@Schema(description = "Standard error response envelope returned by the API")
public class ErrorResponse {

    @Schema(description = "Machine-readable error code", example = "PROMO_NOT_APPLICABLE")
    private String code;

    @Schema(description = "Human-readable error message", example = "Promotion 'SUMMER20' is not active")
    private String message;

    @Schema(description = "HTTP status code", example = "400")
    private int status;

    @Schema(description = "Error timestamp in UTC", example = "2026-03-05T21:38:21.752Z")
    private Instant timestamp;

    public ErrorResponse() {
    }

    public ErrorResponse(String code, String message, int status, Instant timestamp) {
        this.code = code;
        this.message = message;
        this.status = status;
        this.timestamp = timestamp;
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

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
