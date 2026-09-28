package com.positivity.accounting.internal.bankrec.enums;

/** Settlement state of a bank transaction (SPEC §3.2): a {@code PENDING} row is visible, never matchable (D19). */
public enum SettlementState {
    PENDING,
    POSTED
}
