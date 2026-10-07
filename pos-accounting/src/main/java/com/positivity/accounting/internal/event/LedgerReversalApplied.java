package com.positivity.accounting.internal.event;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
 * @param overrideJustification the justification the reversal posted into a closed period with, if any; a
 *     listener that posts a follow-up entry on the reversal date posts it under the same override (#2571)
 * @param reversalReason why the entry was reversed, as the caller gave it; a listener that keeps a record of the
 *     reversed document stores it (a bank deposit, CAP:550 S18, #2514)
 */
public record LedgerReversalApplied(
        @NonNull UUID originalJournalEntryId,
        @NonNull UUID reversalJournalEntryId,
        @NonNull LocalDate reversalDate,
        @NonNull List<UUID> originalLineIds,
        @NonNull Set<UUID> glAccountIds,
        @NonNull String actor,
        @Nullable String overrideJustification,
        @NonNull String reversalReason) {

    public LedgerReversalApplied {
        originalLineIds = List.copyOf(originalLineIds);
        glAccountIds = Set.copyOf(glAccountIds);
    }
}
