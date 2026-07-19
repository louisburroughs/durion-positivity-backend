package com.positivity.accounting.internal.enums;

/**
 * Lifecycle of a processor settlement (story F1c, issue #963).
 *
 * <p>{@code RECEIVED} once the {@code SettlementReportedV1} fact is persisted and its lines matched;
 * {@code POSTED} once the batched journal entry is posted to the ledger (decision D-13). There is no
 * reversed state — a posted settlement JE is corrected via a reversing entry, not a status flip.
 */
public enum SettlementStatus {
    RECEIVED,
    POSTED
}
