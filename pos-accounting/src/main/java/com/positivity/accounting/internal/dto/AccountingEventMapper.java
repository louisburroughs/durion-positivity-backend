package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.JournalEntry;
import org.jspecify.annotations.NonNull;

/**
 * Mapper for converting between JournalEntry entities and
 * AccountingEventResponse DTOs.
 */
public final class AccountingEventMapper {

    private AccountingEventMapper() {
        // Utility class
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
