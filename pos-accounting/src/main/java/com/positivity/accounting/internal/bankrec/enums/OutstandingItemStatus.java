package com.positivity.accounting.internal.bankrec.enums;

/** Status of an outstanding (timing) item (SPEC §3.6, §3.8); behaviour arrives with story S4. */
public enum OutstandingItemStatus {
    OPEN,
    CLEARED,
    CLEARED_IN_GAP,
    VOIDED,
    RELEASED
}
