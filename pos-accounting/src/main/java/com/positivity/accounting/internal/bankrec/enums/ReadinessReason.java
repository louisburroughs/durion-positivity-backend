package com.positivity.accounting.internal.bankrec.enums;

/**
 * Why a reconciliation cannot be submitted or approved yet (SPEC §4.8, E4; story S4, #2303). {@code
 * PROPOSALS_PENDING} is served separately although its rows are already in the unexplained counts;
 * {@code SELF_APPROVAL} (story S5) blocks {@code canApprove} only: the caller submitted it and the tenant does not
 * allow self-approval (D3). {@code OPENING_DIFFERENCE} is never a blocking reason.
 */
public enum ReadinessReason {
    NOT_BALANCED,
    UNEXPLAINED_BANK,
    UNEXPLAINED_LEDGER,
    PROPOSALS_PENDING,
    SELF_APPROVAL
}
