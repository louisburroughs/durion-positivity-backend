package com.positivity.accounting.internal.dto;

import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Traceability response for a journal entry and related records.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JournalEntryTraceabilityResponse {

    private UUID journalEntryId;
    private UUID sourceEventId;
    private JournalEntryResponse journalEntry;
    private JournalEntryResponse originalJournalEntry;
    private JournalEntryResponse reversalJournalEntry;
    private List<JournalEntryResponse> relatedJournalEntries;
}
