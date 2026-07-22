package com.positivity.accounting.internal.enums;

/**
 * Lifecycle of an AR customer credit (issue #992).
 *
 * <p>The status is derived from how much of the issued amount has been consumed — by
 * application against an invoice, by refund, or both:
 *
 * <pre>AVAILABLE → PARTIALLY_CONSUMED → CONSUMED</pre>
 *
 * <p>Each consumption step also relieves the Customer Credit Liability (2300) that the
 * issuance recognized, so a {@code CONSUMED} credit contributes nothing to the control
 * account.
 */
public enum CustomerCreditStatus {

    /** Nothing drawn down yet; the full amount still backs the 2300 liability. */
    AVAILABLE,

    /** Part of the credit has been applied and/or refunded; a residual remains open. */
    PARTIALLY_CONSUMED,

    /** Fully drawn down; the credit's share of the 2300 liability is back to zero. */
    CONSUMED
}
