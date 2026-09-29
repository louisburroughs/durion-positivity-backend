package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A ledger line on the reconciled account as the reconciliation reads it (SPEC §3.9; story S4, #2303):
 * {@code signedAmount} is debit − credit on the asset account (F2 sign convention), {@code date} the
 * entry's transaction date, and {@code reversalEntry} whether the line belongs to an entry that
 * reverses another — one half of a reversal pair, which never counts as unexplained (§3.7).
 */
public record LedgerLine(
        @NonNull UUID lineId,
        @NonNull UUID journalEntryId,
        @Nullable String entryNumber,
        @NonNull LocalDate date,
        @NonNull BigDecimal signedAmount,
        @Nullable String description,
        @Nullable String entryDescription,
        boolean reversalEntry) {

    /** The line of a fetched entry. */
    public static @NonNull LedgerLine of(@NonNull JournalEntryLine line) {
        JournalEntry entry = line.getJournalEntry();
        return new LedgerLine(
                line.getLineId(),
                entry.getJournalEntryId(),
                entry.getEntryNumber(),
                entry.getTransactionDate().toLocalDate(),
                line.getDebitAmount().subtract(line.getCreditAmount()),
                line.getDescription(),
                entry.getDescription(),
                entry.getReversalJournalEntry() != null);
    }
}
