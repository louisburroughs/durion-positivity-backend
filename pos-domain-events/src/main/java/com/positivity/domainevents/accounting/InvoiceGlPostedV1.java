package com.positivity.domainevents.accounting;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code accounting.invoice.gl-posted} v1 on {@code accounting.events.v1} (ADR-0044,
 * #1843).
 *
 * <p>Published by pos-accounting after it posts (or reverses) the revenue journal entry for a
 * finalized invoice: {@code Dr Accounts Receivable / Cr Service Revenue / Cr Sales Tax Payable},
 * dated at the invoice's {@code finalizedAt}. Accounting owns the GL posting fact (ADR-0044 R6);
 * pos-invoice consumes it to move the invoice {@code FINALIZED -> POSTED} and record the real
 * {@code glEntryId}, replacing the simulated in-process posting that never reached the ledger.
 *
 * <p>{@code postingKind} is {@code POSTED} for the revenue entry and {@code REVERSED} for the
 * mirror entry posted when a finalized invoice reverts to {@code DRAFT} (or is cancelled) after
 * it was already recognized. {@code reversedJournalEntryId} is the id of the original revenue
 * entry the reversal offsets; it is {@code null} on {@code POSTED} facts.
 *
 * <p>{@code finalizedAt} identifies the finalization instance the entry belongs to: an invoice
 * that is finalized, reverted, and finalized again produces two {@code POSTED} facts with distinct
 * {@code finalizedAt} values.
 *
 * @param invoiceId the invoice whose revenue was posted or reversed
 * @param journalEntryId the posted journal entry (the revenue entry, or the reversal entry)
 * @param postingKind {@code POSTED} or {@code REVERSED}
 * @param finalizedAt the invoice finalization instant this posting cycle belongs to
 * @param postedAt business time of the journal entry's transaction date
 * @param reversedJournalEntryId on {@code REVERSED}, the revenue entry being reversed
 */
public record InvoiceGlPostedV1(
        @NonNull UUID invoiceId,
        @NonNull UUID journalEntryId,
        @NonNull PostingKind postingKind,
        @NonNull Instant finalizedAt,
        @NonNull Instant postedAt,
        @Nullable UUID reversedJournalEntryId) {

    public static final String EVENT_TYPE = "accounting.invoice.gl-posted";
    public static final int SCHEMA_VERSION = 1;

    /** Whether the fact records the revenue entry itself or its reversal. */
    public enum PostingKind {
        POSTED,
        REVERSED
    }
}
