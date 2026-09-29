package com.positivity.accounting.internal.bankrec.enums;

/**
 * The severity of a close-readiness check (SPEC-manual-bank-reconciliation §5.3; story S6, #2305). Only a
 * {@link #BLOCKING} check can stop a close, and only under a {@code REQUIRED*} policy (except {@code
 * DRAFT_JOURNAL_ENTRIES}, which blocks under every policy).
 */
public enum ReadinessSeverity {
    BLOCKING,
    WARNING,
    INFO
}
