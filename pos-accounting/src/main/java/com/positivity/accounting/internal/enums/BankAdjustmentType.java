package com.positivity.accounting.internal.enums;

/**
 * Bank reconciliation adjustment types (Story F2, issue #965, decision D-6).
 *
 * <p>Each value maps — via the {@code BANK_RECONCILIATION} posting category and a
 * mapping key of the same name — to the counter GL account posted opposite the
 * reconciled cash account when an adjustment is recorded. The frontend never
 * hardcodes this set; it is served by {@code GET /adjustment-types}.
 */
public enum BankAdjustmentType {
    /** Bank service charge — Dr expense / Cr cash. */
    BANK_FEE,
    /** Returned-item (non-sufficient-funds) fee — Dr expense / Cr cash. */
    NSF_FEE,
    /** Interest credited by the bank — Dr cash / Cr income. */
    INTEREST_EARNED,
    /** Deposit-in-transit / float timing correction — clearing account. */
    FLOAT_ADJUSTMENT,
    /** Any other reconciling adjustment — clearing account. */
    OTHER
}
