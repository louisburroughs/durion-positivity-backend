package com.positivity.order.internal.exception;

/**
 * A structurally valid return request that a domain rule refuses on its merits: a refund method
 * that requires a customer the return does not carry, no invoice on the original order to refund
 * against, or insufficient settled original tender to cover the return total (issue #1730).
 * ADR-0017 §2 makes that a {@code 422}; maps to {@code RETURN_UNPROCESSABLE}.
 *
 * <p>These answered {@code 409} while they travelled as bare {@link IllegalStateException},
 * which told a caller its request collided with current state when in fact the request is
 * refused on its own terms — a distinction that decides whether retrying unchanged can ever work.
 */
public class ReturnOrderUnprocessableException extends RuntimeException {

    public ReturnOrderUnprocessableException(String message) {
        super(message);
    }
}
