package com.positivity.people.internal.exception;

/**
 * A stateful collision in this module — the request itself is well-formed, but the current
 * status of the resource being acted on blocks the requested transition (an already-terminal
 * lifecycle state, an already-disabled employee). Controllers and services must throw this
 * (never bare {@code IllegalStateException}) for that case: {@code PeopleExceptionHandler} maps
 * it to {@code 409} per ADR-0017 §2, echoing the message.
 *
 * <p>Bare {@code IllegalStateException} must not be used for this because it is not exclusive
 * to a lifecycle guard — it is also what {@code SecurityContextHelper}'s {@code
 * orElseThrow(() -> new IllegalStateException("No current user"))} throws on a missing security
 * context (a server-side/auth defect, not a resource-state collision), and what the JDK and
 * Spring throw internally for all manner of unrelated misuse. Re-typing a lifecycle guard from
 * {@code IllegalArgumentException} to bare {@code IllegalStateException} only moves the #1694
 * bug class rather than removing it: a type this module controls, thrown only where the module
 * itself detects a blocked state transition, cannot be confused with an unrelated defect —
 * anything else typed {@code IllegalStateException} keeps answering through the pre-existing
 * generic {@code IllegalStateException} handler (or, in the case of a missing security context,
 * ought to fall through to the platform's 500 handler, tracked separately from this issue).
 *
 * <p>A check on the shape or contents of the request itself (a blank required field, an
 * unparseable format) is request-shape validation, not a stateful collision, and belongs on
 * {@link RequestValidationException} (mapped {@code 400}) — not this type.
 */
public class ResourceStateConflictException extends RuntimeException {

    public ResourceStateConflictException(String message) {
        super(message);
    }
}
