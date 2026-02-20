package com.positivity.price.internal.dto;

import java.time.Instant;

/**
 * Standard API error response envelope.
 */
public class ErrorResponse {

    private String code;
    private String message;
    private int status;
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
