package com.positivity.accounting.internal.dto;

import org.jspecify.annotations.NonNull;

import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;

/**
 * Mapper for converting between ReprocessingAttemptHistory entities and DTOs.
 */
public final class ReprocessingAttemptHistoryMapper {

    private ReprocessingAttemptHistoryMapper() {
        // Utility class
    }

    /**
     * Converts a ReprocessingAttemptHistory entity to a DTO.
     */
    public static ReprocessingAttemptHistoryResponse toResponse(@NonNull ReprocessingAttemptHistory entity) {
        return ReprocessingAttemptHistoryResponse.builder()
                .attemptId(entity.getAttemptId())
                .eventId(entity.getAccountingEvent().getEventId())
                .attemptedAt(entity.getAttemptedAt())
                .triggeredByUserId(entity.getTriggeredByUserId())
                .outcome(entity.getOutcome())
                .outcomeDetails(entity.getOutcomeDetails())
                .mappingVersionUsed(entity.getMappingVersionUsed())
                .build();
    }
}
