package com.positivity.accounting.internal.bankrec.enums;

/**
 * Whether a period may close without bank reconciliation readiness (SPEC-manual-bank-reconciliation §5.2, D4;
 * story S6, #2305). Stored in {@code accounting_configuration} under {@code BANK_REC_CLOSE_POLICY}; absent means
 * {@link #REQUIRED_WITH_EXCEPTION}.
 */
public enum BankRecClosePolicy {
    /** Readiness is reported, close is never blocked; only DRAFT entries block, an exception body is ignored. */
    ADVISORY,
    /** BLOCKING checks block close unless an exception is granted ({@code close} + {@code override} + justification). */
    REQUIRED_WITH_EXCEPTION,
    /** BLOCKING checks block close; an exception is refused. */
    REQUIRED;

    /** Whether BLOCKING bank reconciliation checks stop the close under this policy. */
    public boolean blocksClose() {
        return this != ADVISORY;
    }
}
