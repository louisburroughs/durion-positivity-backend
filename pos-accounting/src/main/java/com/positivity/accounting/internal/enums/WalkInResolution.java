package com.positivity.accounting.internal.enums;

/**
 * How an unpaid walk-in sale is resolved (CAP:550 S11, #2508; SPEC-accounting-workspace §5.1 "Unpaid
 * walk-in sale"). Each is an EXISTING command; the read never posts.
 *
 * <p>Reassign to the real customer is not offered: reassigning a finalised invoice is an open
 * Invoicing &amp; Payments decision (§12 OI-5). It joins this list once decided.
 */
public enum WalkInResolution {
    /** Take the money: payment capture in pos-invoice, which the settled payment then applies. */
    COLLECT,
    /** Issue a credit memo against the invoice in accounting. */
    CREDIT_MEMO
}
