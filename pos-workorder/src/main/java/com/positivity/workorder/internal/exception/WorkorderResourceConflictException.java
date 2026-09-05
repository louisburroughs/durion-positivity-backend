package com.positivity.workorder.internal.exception;

/**
 * A well-formed request cannot be applied because of the target resource's current state: an
 * invalid lifecycle transition, such as approving an estimate that is not PENDING_APPROVAL or a
 * workorder that is not DRAFT (issue #1753), or a quantity operation that would exceed what the
 * resource's current running totals actually have available (issue #1694).
 *
 * <p>Note: a caller-supplied id that does not match the resource it targets (e.g. an approving
 * customerId that is not the estimate's or workorder's own customer) is deliberately NOT this
 * type. The resource itself is otherwise perfectly actionable — the caller simply put the wrong
 * id in the request body — so that is payload validation against the addressed resource
 * ({@link WorkorderRequestValidationException}, {@code 400} per ADR-0017 §1), not a stateful
 * collision. Only use this type where the request cannot be re-issued correctly regardless of
 * payload — where the identical body would succeed once the resource's state changes.
 *
 * <p>Answered as {@code 409}. ADR-0017 §2 names "invalid lifecycle transition" among its 409
 * cases directly, so the approval state checks are not a judgement call. Carries the same
 * {@code CONFLICT} code as the module's catch-all {@code IllegalStateException} handler, so every
 * stateful-collision response in this module reads identically to a client.
 *
 * <p>Prefer this type over a bare {@code IllegalStateException} even though that handler also
 * answers 409. {@code IllegalStateException} is thrown by the JDK and by libraries for reasons
 * that have nothing to do with this domain, and mapping it wholesale is the same defect #1694
 * removed for {@code IllegalArgumentException}: a server fault reported to the caller as though
 * they had done something wrong. Typing the throw keeps the 409 to conditions we actually mean.
 */
public class WorkorderResourceConflictException extends RuntimeException {

    public static final String ERROR_CODE = "CONFLICT";

    public WorkorderResourceConflictException(String message) {
        super(message);
    }
}
