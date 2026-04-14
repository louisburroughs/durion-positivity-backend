package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.JournalEntryTraceabilityResponse;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface JournalEntryService {

    /**
     * Creates a new draft journal entry.
     * Entry must be balanced: sum of debits = sum of credits.
     * GL accounts are validated but not locked (allow posting to DRAFT entries
     * simultaneously).
     *
     * @param entry journal entry with lines to create
     * @return created entry in DRAFT status
     * @throws IllegalArgumentException if entry is unbalanced or GL accounts
     *                                  invalid
     */
    JournalEntry createJournalEntry(JournalEntry entry);

    /**
     * Retrieves an existing journal entry by ID.
     *
     * <p>
     * <strong>Note:</strong> Internal callers within this class must use
     * {@link #findById(UUID)} instead, because self-invocation bypasses the
     * Spring proxy and the {@code readOnly} hint would not be applied.
     * </p>
     */
    JournalEntry getJournalEntry(UUID journalEntryId);

    /**
     * Retrieves traceability details for a journal entry, including related entries
     * linked by source event and reversal relationships when available.
     *
     * @param journalEntryId journal entry identifier
     * @return traceability response
     */
    JournalEntryTraceabilityResponse getJournalTraceability(UUID journalEntryId);

    /**
     * Updates a draft journal entry (DRAFT status only).
     * Once POSTED, entries are immutable; use reverse() instead.
     *
     * @param journalEntryId entry to update
     * @param updates        journal entry with new values
     * @return updated entry
     * @throws IllegalStateException if entry is not in DRAFT status
     */
    JournalEntry updateJournalEntry(UUID journalEntryId, JournalEntry updates);

    /**
     * Posts a draft journal entry to GL (transitions to POSTED).
     * Immutable thereafter; GL account balances updated.
     *
     * @param journalEntryId entry to post
     * @return posted entry
     * @throws IllegalStateException if entry is not in DRAFT status or is
     *                               unbalanced
     */
    JournalEntry postJournalEntry(UUID journalEntryId);

    /**
     * Reverses a posted journal entry by creating an inverse entry.
     * Original entry remains POSTED; reversal entry appears as REVERSED.
     * Reversal entries are immediately POSTED (no DRAFT → POSTED transition).
     *
     * @param originalEntryId entry to reverse
     * @param reversalReason  reason for reversal (e.g., "CORRECTION", "ADJUSTMENT")
     * @return reversal journal entry
     * @throws IllegalArgumentException if original entry not found or not POSTED
     */
    JournalEntry reverseJournalEntry(UUID originalEntryId, String reversalReason);

    /**
     * Lists journal entries with pagination and filtering.
     */
    Page<JournalEntry> listJournalEntries(Pageable pageable);

    /**
     * Find all posted entries for audit or reconciliation.
     */
    Page<JournalEntry> listPostedEntries(Pageable pageable);

    /**
     * Find entries by status (DRAFT, POSTED, REVERSED).
     */
    List<JournalEntry> findByStatus(JournalEntryStatus status);
}
