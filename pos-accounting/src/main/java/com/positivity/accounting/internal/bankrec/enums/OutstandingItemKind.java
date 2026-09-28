package com.positivity.accounting.internal.bankrec.enums;

/** Kind of an outstanding (timing) item (SPEC §3.6). */
public enum OutstandingItemKind {
    DEPOSIT_IN_TRANSIT,
    OUTSTANDING_CHECK,
    OTHER_LEDGER_TIMING,
    BANK_ERROR_PENDING
}
