package com.positivity.order.internal.exception;

/**
 * A well-formed return request that the current lifecycle state refuses: a return against an
 * order that is not COMPLETED, an approval or rejection of a return that is not
 * PENDING_APPROVAL, a saga start without RETURN_REQUESTED, or a retry from a state other than
 * REFUND_FAILED (issue #1730). ADR-0017 §2 makes a stateful collision a {@code 409}; maps to
 * {@code RETURN_INVALID_STATE}, the code and status this case already answered.
 *
 * <p>Split out of bare {@link IllegalStateException}, which in this service also carried
 * domain-policy refusals ({@link ReturnOrderUnprocessableException}) and outright downstream
 * failures — the latter answering a client 409 for what is a server-side problem.
 */
public class ReturnOrderStateConflictException extends RuntimeException {

    public ReturnOrderStateConflictException(String message) {
        super(message);
    }
}
