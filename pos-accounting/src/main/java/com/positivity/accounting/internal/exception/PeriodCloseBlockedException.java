package com.positivity.accounting.internal.exception;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when a period cannot be closed because DRAFT journal entries are
 * dated inside it. Carries the blocking entry IDs so the API layer can list
 * them in the 422 PERIOD_HAS_DRAFT_ENTRIES payload (story B1 acceptance
 * criterion).
 */
public class PeriodCloseBlockedException extends RuntimeException {

    private final String periodCode;
    private final List<UUID> draftJournalEntryIds;

    public PeriodCloseBlockedException(String periodCode, List<UUID> draftJournalEntryIds) {
        super("Cannot close period " + periodCode + ": " + draftJournalEntryIds.size()
                + " DRAFT journal entries are dated inside the period");
        this.periodCode = periodCode;
        this.draftJournalEntryIds = List.copyOf(draftJournalEntryIds);
    }

    public String getPeriodCode() {
        return periodCode;
    }

    public List<UUID> getDraftJournalEntryIds() {
        return draftJournalEntryIds;
    }
}
