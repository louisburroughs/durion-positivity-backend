package com.positivity.accounting.internal.exception;

import com.positivity.accounting.internal.enums.JournalEntryStatus;
import java.util.UUID;

/**
 * Thrown when a reversal is requested against a journal entry whose current
 * status forbids it: only POSTED entries are reversible. Maps to 409 with
 * code JE_ALREADY_REVERSED (double reversal — including a lost concurrent
 * reversal race) or JE_NOT_POSTED (DRAFT/PENDING) depending on the status
 * (story A3, issue #943).
 */
public class JournalEntryNotReversibleException extends RuntimeException {

    private final UUID journalEntryId;
    private final JournalEntryStatus currentStatus;

    public JournalEntryNotReversibleException(UUID journalEntryId, JournalEntryStatus currentStatus) {
        super("Cannot reverse " + currentStatus + " journal entry " + journalEntryId
                + "; only POSTED entries can be reversed");
        this.journalEntryId = journalEntryId;
        this.currentStatus = currentStatus;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public JournalEntryStatus getCurrentStatus() {
        return currentStatus;
    }
}
