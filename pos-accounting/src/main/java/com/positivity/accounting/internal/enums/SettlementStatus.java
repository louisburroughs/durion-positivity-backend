package com.positivity.accounting.internal.enums;

/**
 * Lifecycle of a processor settlement (story F1c, issue #963).
 *
 * <p>{@code RECEIVED} once the {@code SettlementReportedV1} fact is persisted and its lines matched;
 * {@code POSTED} once the batched journal entry is posted to the ledger (decision D-13). There is no
 * reversed state — a posted settlement JE is corrected via a reversing entry, not a status flip.
 *
 * <p>{@code REJECTED} is a terminal ingest-time state for settlements that cannot be auto-posted
 * without producing an incoherent journal entry: a non-base currency (no FX handling in F1c) or a
 * matched gross exceeding the header gross (which would make the suspense leg negative, including
 * net-negative-gross payouts). Rejected settlements are never enqueued for posting and are surfaced
 * for manual reconciliation.
 */
public enum SettlementStatus {
    RECEIVED,
    POSTED,
    REJECTED
}
