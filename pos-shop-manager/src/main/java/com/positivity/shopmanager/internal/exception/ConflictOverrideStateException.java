package com.positivity.shopmanager.internal.exception;

/**
 * An override that cannot be recorded because of the conflict's current state — already overridden
 * — as opposed to a request that is malformed (400) or names a HARD conflict, which answers with the
 * DECISION-SHOPMGMT-002 envelope via {@link SchedulingConflictException}. Maps to 409 {@code
 * CONFLICT_ALREADY_OVERRIDDEN}.
 */
public class ConflictOverrideStateException extends RuntimeException {
    public static final String CODE = "CONFLICT_ALREADY_OVERRIDDEN";

    public ConflictOverrideStateException(String message) {
        super(message);
    }
}
