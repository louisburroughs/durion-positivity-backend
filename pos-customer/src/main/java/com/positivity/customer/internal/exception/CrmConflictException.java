package com.positivity.customer.internal.exception;

/**
 * The request is well formed but conflicts with the module's current state, and no natural-key
 * duplicate is involved. Maps to {@code 409}.
 *
 * <p>Distinct from {@link CrmDuplicateResourceException}, which says a resource with the same
 * natural key already exists — a message and an error code that would be actively misleading for
 * the conflicts this covers. The first of those is a fact replay requested while fact publication
 * is disabled (issue #1893): the request is valid and will succeed once publication is on.
 */
public class CrmConflictException extends RuntimeException {

    public CrmConflictException(String message) {
        super(message);
    }
}
