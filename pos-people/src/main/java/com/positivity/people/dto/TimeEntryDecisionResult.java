package com.positivity.people.dto;

public class TimeEntryDecisionResult {
    private String timeEntryId;
    private boolean success;
    private String errorCode;
    private String message;

    public TimeEntryDecisionResult() {
    }

    public TimeEntryDecisionResult(String timeEntryId, boolean success, String errorCode, String message) {
        this.timeEntryId = timeEntryId;
        this.success = success;
        this.errorCode = errorCode;
        this.message = message;
    }

    public String getTimeEntryId() {
        return timeEntryId;
    }

    public void setTimeEntryId(String timeEntryId) {
        this.timeEntryId = timeEntryId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
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
}
