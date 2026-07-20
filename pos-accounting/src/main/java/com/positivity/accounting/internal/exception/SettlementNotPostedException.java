package com.positivity.accounting.internal.exception;

/**
 * Thrown when a manual match or write-off is attempted on a settlement whose
 * batched journal entry has not yet posted (still RECEIVED). Manual adjustments
 * must wait for the async posting so they do not double-clear Undeposited Funds
 * or strand a Suspense debit (story F1c, issue #963, PR #977 finding 2). Maps to
 * 409 SETTLEMENT_NOT_POSTED.
 */
public class SettlementNotPostedException extends RuntimeException {

    public SettlementNotPostedException(String message) {
        super(message);
    }
}
