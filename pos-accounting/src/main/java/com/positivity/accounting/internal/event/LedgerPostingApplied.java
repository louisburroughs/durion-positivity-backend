package com.positivity.accounting.internal.event;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * In-process Spring event (never a Kafka fact): a journal entry was posted, published by {@code
 * JournalEntryServiceImpl.postJournalEntry} after the status flip, in the posting transaction, so the bank
 * reconciliation's ledger-change hook can invalidate an approved window the posting lands in
 * (SPEC-manual-bank-reconciliation §5.5; story S5, #2304). Listeners run synchronously in the same
 * transaction; the posting itself is never refused by them (D11).
 *
 * @param journalEntryId the posted entry
 * @param transactionDate the date every line of the entry is booked on
 * @param glAccountIds the accounts its lines touch
 * @param actor who posted it (ADR-0018)
 */
public record LedgerPostingApplied(
        @NonNull UUID journalEntryId,
        @NonNull LocalDate transactionDate,
        @NonNull Set<UUID> glAccountIds,
        @NonNull String actor) {

    public LedgerPostingApplied {
        glAccountIds = Set.copyOf(glAccountIds);
    }
}
