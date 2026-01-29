package com.positivity.people.internal.dto;

import java.util.UUID;

public class TimeEntryExceptionResponse {
    private UUID exceptionId;
    private boolean success;
    private String message;

    public TimeEntryExceptionResponse() {
    }

    public TimeEntryExceptionResponse(UUID exceptionId, boolean success, String message) {
        this.exceptionId = exceptionId;
        this.success = success;
        this.message = message;
    }

    public UUID getExceptionId() {
        return exceptionId;
    }

    public void setExceptionId(UUID exceptionId) {
        this.exceptionId = exceptionId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
