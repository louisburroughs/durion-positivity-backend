package com.positivity.accounting.internal.exception;

/**
 * Thrown when a journal entry is not found by ID. Maps to HTTP 404
 * (JOURNAL_ENTRY_NOT_FOUND).
 */
public class JournalEntryNotFoundException extends RuntimeException {

    public JournalEntryNotFoundException(String message) {
        super(message);
    }
}
