package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import org.jspecify.annotations.NonNull;

/**
 * Mapper for converting between AccountingEvent/JournalEntry entities and
 * AccountingEventResponse DTOs.
 */
public final class AccountingEventMapper {

    private AccountingEventMapper() {
        // Utility class
    }

    /**
     * Converts an AccountingEvent entity to an AccountingEventResponse DTO.
     */
    public static AccountingEventResponse toEventResponse(@NonNull AccountingEvent entity) {
        return AccountingEventResponse.builder()
                .eventId(entity.getEventId())
                .eventType(entity.getEventType())
                .transactionDate(entity.getTransactionDate())
                .payload(entity.getPayload())
                .status(entity.getStatus())
                .journalEntryId(entity.getJournalEntryId())
                .errorMessage(entity.getErrorMessage())
                .receivedAt(entity.getReceivedAt())
                .processedAt(entity.getProcessedAt())
                .sequenceNumber(entity.getSequenceNumber())
                // Suspense queue fields (CAP:055)
                .failureReasonCode(entity.getFailureReasonCode())
                .failureDetails(entity.getFailureDetails())
                .attemptCount(entity.getAttemptCount())
                .finalPostingReferenceId(entity.getFinalPostingReferenceId())
                .resolvedByUserId(entity.getResolvedByUserId())
                .mappingVersionAttempted(entity.getMappingVersionAttempted())
                .build();
    }

    /**
     * Converts a JournalEntry entity to an AccountingEventResponse DTO.
     */
    public static AccountingEventResponse toEventResponse(@NonNull JournalEntry entity) {
        AccountingEventResponse response = new AccountingEventResponse();
        response.setEventId(entity.getSourceEventId());
        response.setEventType(entity.getSourceEventType());
        response.setTransactionDate(entity.getTransactionDate());
        response.setJournalEntryId(entity.getJournalEntryId());
        response.setStatus("PROCESSED");
        response.setReceivedAt(entity.getCreatedAt());
        response.setProcessedAt(entity.getPostedAt() != null ? entity.getPostedAt() : entity.getModifiedAt());
        return response;
    }
}
