package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.TimeEntry;

public final class TimeEntryMapper {

    private TimeEntryMapper() {}

    public static TimeEntryResponse toResponse(TimeEntry entry) {
        return TimeEntryResponse.builder()
                .timeEntryId(entry.getTimeEntryId())
                .personId(entry.getPersonId())
                .workOrderId(entry.getWorkOrderId())
                .startAt(entry.getStartAt())
                .endAt(entry.getEndAt())
                .status(entry.getStatus())
                .submittedAt(entry.getSubmittedAt())
                .decisionByUserId(entry.getDecisionByUserId())
                .decisionAtUtc(entry.getDecisionAtUtc())
                .rejectionReason(entry.getRejectionReason())
                .createdAt(entry.getCreatedAt())
                .updatedAt(entry.getUpdatedAt())
                .build();
    }
}
