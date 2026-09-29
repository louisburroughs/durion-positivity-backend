package com.positivity.accounting.internal.bankrec.enums;

/**
 * Why an approved reconciliation became {@code INVALIDATED} (SPEC-manual-bank-reconciliation §3.8, §3.10,
 * §5.5; story S5, #2304). Stored in {@code bank_reconciliation.invalidation_reason} and carried by the
 * {@code accounting.bankreconciliation.invalidated} fact. {@code SOURCE_REMOVED} is written by the phase-2
 * feed listener; the value ships now so the fact's contract is complete.
 */
public enum InvalidationReason {
    /** A ledger line of an active match, or an outstanding item's line, was reversed. */
    LEDGER_LINE_REVERSED,
    /** A cash line was posted dated inside the approved window. */
    LEDGER_LINE_POSTED,
    /** The feed reported a matched transaction removed (phase 2). */
    SOURCE_REMOVED,
    /** The reconciled statement was superseded by a corrected re-import (§4.9 path 3). */
    STATEMENT_SUPERSEDED
}
