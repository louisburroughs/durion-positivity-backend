package com.positivity.accounting.internal.enums;

/**
 * Bank reconciliation adjustment types (Story F2, issue #965, decision D-6 as
 * amended by the accounting-owner ruling).
 *
 * <p>Each value maps — via the {@code BANK_RECONCILIATION} posting category and a
 * mapping key of the same name — to the counter GL account posted opposite the
 * reconciled cash account when an adjustment is recorded. The frontend never
 * hardcodes this set; it is served by {@code GET /adjustment-types}.
 *
 * <p>The economic meaning of most types is one-directional, so each carries a
 * {@link RequiredSign} constraint enforced at adjustment time: a bank/NSF fee can
 * only reduce cash, bank interest can only increase it. {@code FLOAT_ADJUSTMENT}
 * is deliberately absent in v1 — a timing difference (deposit-in-transit /
 * outstanding check) is where the book is already correct and the bank is late,
 * so posting a cash-moving JE for it would create phantom cash; it returns as a
 * non-posting outstanding item in the reconciliation fast-follow.
 */
public enum BankAdjustmentType {
    /** Bank service charge — Cr cash / Dr expense. Reduces cash (negative only). */
    BANK_FEE(RequiredSign.NEGATIVE_ONLY),
    /** Returned-item (non-sufficient-funds) fee — Cr cash / Dr expense. Reduces cash (negative only). */
    NSF_FEE(RequiredSign.NEGATIVE_ONLY),
    /** Interest credited by the bank — Dr cash / Cr income. Increases cash (positive only). */
    INTEREST_EARNED(RequiredSign.POSITIVE_ONLY),
    /** Any other reconciling adjustment the accountant chooses to book — clearing account, either sign. */
    OTHER(RequiredSign.ANY);

    /** Sign constraint on the adjustment amount for a type. */
    public enum RequiredSign {
        POSITIVE_ONLY,
        NEGATIVE_ONLY,
        ANY
    }

    private final RequiredSign requiredSign;

    BankAdjustmentType(RequiredSign requiredSign) {
        this.requiredSign = requiredSign;
    }

    public RequiredSign requiredSign() {
        return requiredSign;
    }

    /** True if the given amount signum is permitted for this type (zero is never permitted). */
    public boolean permits(int signum) {
        return switch (requiredSign) {
            case POSITIVE_ONLY -> signum > 0;
            case NEGATIVE_ONLY -> signum < 0;
            case ANY -> signum != 0;
        };
    }
}
