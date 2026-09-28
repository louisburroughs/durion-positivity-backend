package com.positivity.accounting.internal.bankrec.enums;

/**
 * Status of a bank transaction (SPEC §3.2, §3.8). {@code UNMATCHED ↔ MATCHED} is the only transition
 * story S1 uses; the others arrive with the intake (S2), matching (S4) and supersession (S5).
 */
public enum BankTransactionStatus {
    UNMATCHED,
    POSSIBLE_DUPLICATE,
    MATCHED,
    EXCLUDED,
    REMOVED_BY_SOURCE
}
