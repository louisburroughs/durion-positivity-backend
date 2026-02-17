package com.positivity.people.internal.dto;

import java.util.UUID;

public class TimeEntryIssueResponse {
    private UUID issueId;
    private boolean success;
    private String message;

    public TimeEntryIssueResponse() {
    }

    public TimeEntryIssueResponse(UUID issueId, boolean success, String message) {
        this.issueId = issueId;
        this.success = success;
        this.message = message;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public void setIssueId(UUID issueId) {
        this.issueId = issueId;
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
