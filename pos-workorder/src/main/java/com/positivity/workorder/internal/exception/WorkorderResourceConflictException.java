package com.positivity.workorder.internal.exception;

/**
 * A well-formed request cannot be applied because of the target resource's current state: a
 * quantity operation would exceed what the resource's current running totals actually have
 * available (issue #1694).
 *
 * <p>Note: a caller-supplied id that does not match the resource it targets (e.g. an approving
 * customerId that is not the estimate's or workorder's own customer) is deliberately NOT this
 * type. The resource itself is otherwise perfectly actionable — the caller simply put the wrong
 * id in the request body — so that is payload validation against the addressed resource
 * ({@link WorkorderRequestValidationException}, {@code 400} per ADR-0017 §1), not a stateful
 * collision. Only use this type where the request cannot be re-issued correctly regardless of
 * payload (e.g. a quantity the resource's own running totals do not have available).
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
