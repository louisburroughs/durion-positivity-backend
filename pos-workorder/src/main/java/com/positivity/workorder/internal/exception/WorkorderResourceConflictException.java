package com.positivity.workorder.internal.exception;

/**
 * A well-formed request cannot be applied because of the target resource's current state: an id
 * on the request does not match the resource it targets (e.g. an approving customerId that is
 * not the workorder's or estimate's actual customer), or a quantity operation would exceed what
 * the resource's current running totals actually have available (issue #1694).
 *
 * <p>Answered as {@code 409} (ADR-0017 §2 — "resource/system state... business state
 * collisions"), with the same {@code CONFLICT} code the module's existing
 * {@code IllegalStateException} handler already uses for lifecycle-transition conflicts, so every
 * stateful-collision response in this module carries one consistent code.
 */
public class WorkorderResourceConflictException extends RuntimeException {

    public static final String ERROR_CODE = "CONFLICT";

    public WorkorderResourceConflictException(String message) {
        super(message);
    }
}
