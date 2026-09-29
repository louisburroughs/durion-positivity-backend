package com.positivity.accounting.internal.event;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * In-process Spring event (never a Kafka fact): a posted journal entry was reversed, published by {@code
 * JournalEntryServiceImpl.reverseJournalEntry} after {@code markReversed}, in the reversal transaction, so the
 * bank reconciliation's ledger-change hook can break the matches, void the outstanding items and invalidate the
 * approvals that rested on the original's lines (SPEC-manual-bank-reconciliation §5.5; story S5, #2304). The
 * reversal proceeds whatever the hook finds (D11).
 *
 * @param originalJournalEntryId the reversed entry
 * @param reversalJournalEntryId the entry that reverses it
 * @param reversalDate the reversal's transaction date
 * @param originalLineIds the lines of the reversed entry
 * @param glAccountIds the accounts those lines touch
 * @param actor who reversed it (ADR-0018)
 */
public record LedgerReversalApplied(
        @NonNull UUID originalJournalEntryId,
        @NonNull UUID reversalJournalEntryId,
        @NonNull LocalDate reversalDate,
        @NonNull List<UUID> originalLineIds,
        @NonNull Set<UUID> glAccountIds,
        @NonNull String actor) {

    public LedgerReversalApplied {
        originalLineIds = List.copyOf(originalLineIds);
        glAccountIds = Set.copyOf(glAccountIds);
    }
}
