package com.positivity.people.dto;

import java.time.OffsetDateTime;

public class TimeEntryAdjustmentRequest {
    private String timeEntryId;
    private String reasonCode;
    private String notes;
    private OffsetDateTime proposedStartAt;
    private OffsetDateTime proposedEndAt;
    private Integer minutesDelta;
    private String createdBy;

    public String getTimeEntryId() {
        return timeEntryId;
    }

    public void setTimeEntryId(String timeEntryId) {
        this.timeEntryId = timeEntryId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public OffsetDateTime getProposedStartAt() {
        return proposedStartAt;
    }

    public void setProposedStartAt(OffsetDateTime proposedStartAt) {
        this.proposedStartAt = proposedStartAt;
    }

    public OffsetDateTime getProposedEndAt() {
        return proposedEndAt;
    }

    public void setProposedEndAt(OffsetDateTime proposedEndAt) {
        this.proposedEndAt = proposedEndAt;
    }

    public Integer getMinutesDelta() {
        return minutesDelta;
    }

    public void setMinutesDelta(Integer minutesDelta) {
        this.minutesDelta = minutesDelta;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
