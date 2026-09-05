package com.positivity.order.internal.exception;

/**
 * A cancellation retry that failed again, after the order has been parked at
 * {@code CANCEL_REQUIRES_MANUAL_REVIEW} and the review-required fact published. Automation is
 * exhausted: retrying will keep failing until a human resolves the billing leg.
 *
 * <p>Answers {@code 500}, which is what {@code retryOrderCancellation}'s own documentation
 * already promises for this outcome — the underlying cause is a downstream reversal that did not
 * succeed, not anything the caller can correct in its request. Before issue #1730 this was a bare
 * {@link IllegalStateException} relying on no advice in the module mapping the type; naming it
 * means the status comes from the exception class rather than from an absence, which is what
 * ADR-0017 §2's "encode the status on the domain exception class" asks for.
 *
 * <p>The typed form also lets the response carry {@link #NEXT_ACTION}. The endpoint's prose says
 * the order is parked for manual review, but the platform fallback body could not say so, leaving
 * the caller a bare {@code INTERNAL_ERROR} with no way to distinguish this from any other 500.
 */
public class OrderCancellationReviewRequiredException extends RuntimeException {

    /** ADR-0017 §3 recovery hint: the order is parked and needs a human, not another retry. */
    public static final String NEXT_ACTION = "MANUAL_REVIEW_REQUIRED";

    public OrderCancellationReviewRequiredException(String message) {
        super(message);
    }
}
