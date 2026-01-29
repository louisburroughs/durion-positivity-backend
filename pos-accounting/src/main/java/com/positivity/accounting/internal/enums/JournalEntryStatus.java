package com.positivity.accounting.internal.enums;

/**
 * Journal Entry lifecycle states.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide</a>
 */
public enum JournalEntryStatus {
    /**
     * Entry is in draft state, editable, not posted to GL.
     */
    DRAFT,

    /**
     * Entry is pending review/approval before posting.
     */
    PENDING,

    /**
     * Entry is posted to GL, immutable, affects account balances.
     */
    POSTED,

    /**
     * Entry has been reversed by a reversing journal entry.
     */
    REVERSED
}
