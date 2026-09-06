package com.positivity.workorder.internal.exception;

/**
 * A DRAFT estimate cannot be submitted for customer approval because it is not yet complete: it
 * has no customer, no vehicle, no line items, or its totals have not been calculated (CAP:003
 * Issue #168, issue #1791).
 *
 * <p>Answered as {@code 422} (ADR-0017 §2). The submit request is a bare POST with no body, so
 * there is nothing about the payload to correct — it is not a {@code 400}. Nor is it a {@code 409}:
 * the estimate <em>is</em> in DRAFT, the one lifecycle status that permits submission, and what
 * refuses the request is an attribute of the target other than its status. ADR-0017 §2 names
 * exactly this shape ("a claim with no lines, missing required evidence") among its 422 cases,
 * and the client's remedy is a different operation first — assign the customer or vehicle, add a
 * line, call the calculate endpoint — not a re-read of the target's state.
 *
 * <p>Contrast {@link WorkorderResourceConflictException}, which is the answer when the estimate
 * is <em>not</em> DRAFT: that refusal is the target's lifecycle status and is a {@code 409}.
 */
public class EstimateIncompleteException extends RuntimeException {

    public static final String ERROR_CODE = "ESTIMATE_INCOMPLETE";

    public EstimateIncompleteException(String message) {
        super(message);
    }
}
