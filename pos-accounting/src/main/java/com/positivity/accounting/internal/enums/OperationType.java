package com.positivity.accounting.internal.enums;

/**
 * Operation type for statement line calculations.
 *
 * <p>The amount an operation applies to is the account's balance on its normal side (assets and
 * expenses: debits minus credits; liabilities, equity and revenue: credits minus debits), so a
 * revenue or liability account mapped with {@link #SUM} reads as a positive amount (issue #2394).
 */
public enum OperationType {
    /** Add this account's normal-side balance to the line total */
    SUM,
    /** Subtract this account's normal-side balance from the line total (e.g., contra presentation) */
    SUBTRACT,
    /**
     * Add credits minus debits, whatever the account type. Predates normal-side signing, when it
     * was how a credit-normal account was shown as a positive amount: on such an account it now
     * equals {@link #SUM} (the sign is not flipped twice); on a debit-normal account it reverses
     * the balance.
     */
    NEGATE
}
