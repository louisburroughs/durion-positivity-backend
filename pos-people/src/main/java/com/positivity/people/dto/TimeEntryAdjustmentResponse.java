package com.positivity.people.dto;

import java.util.UUID;

public class TimeEntryAdjustmentResponse {
    private UUID adjustmentId;
    private boolean success;
    private String message;

    public TimeEntryAdjustmentResponse() {
    }

    public TimeEntryAdjustmentResponse(UUID adjustmentId, boolean success, String message) {
        this.adjustmentId = adjustmentId;
        this.success = success;
        this.message = message;
    }

    public UUID getAdjustmentId() {
        return adjustmentId;
    }

    public void setAdjustmentId(UUID adjustmentId) {
        this.adjustmentId = adjustmentId;
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
