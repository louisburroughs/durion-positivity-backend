package com.positivity.accounting.internal.exception;

/**
 * Thrown when a single AR payment-application reversal targets one application of
 * a multi-application apply request (story C2, issue #958, PR #977 finding 3).
 *
 * <p>The C1 cash-receipt journal entry is batched per {@code applicationRequestId}
 * (one JE covering every application of the request), and the C2 reversing entry
 * is keyed the same way. Reversing a single application of a multi-invoice request
 * would therefore fully reverse the batched JE and desync the ledger from the
 * remaining live applications. Such a request must be reversed as a whole (reverse
 * the payment), never one application at a time. Maps to 422
 * WHOLE_REQUEST_REVERSAL_REQUIRED.
 */
public class MultiApplicationReversalException extends RuntimeException {

    public MultiApplicationReversalException(String message) {
        super(message);
    }
}
