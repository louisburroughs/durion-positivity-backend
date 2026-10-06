package com.positivity.order.internal.exception;

import org.jspecify.annotations.NonNull;

/**
 * A walk-in cart (its customer is the tenant's CASH house account) was asked to do something only
 * a registered customer may do (CAP:550 S8, decision AW12): charge the sale on account, take a
 * deposit, or carry a workorder link. Maps to {@code 422 ORDER_WALK_IN_NOT_ALLOWED} with the
 * reason in {@code fieldErrors[walkIn]}.
 */
public class WalkInNotAllowedException extends RuntimeException {

    /** Why the walk-in customer is refused; the name is the wire value in {@code fieldErrors[walkIn]}. */
    public enum Reason {
        ON_ACCOUNT("A walk-in sale cannot be charged on account; choose a registered customer"),
        DEPOSIT("A deposit cannot be taken for a walk-in customer; choose a registered customer"),
        WORKORDER_LINK("A workorder cannot be linked to a walk-in sale; choose a registered customer");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    public WalkInNotAllowedException(@NonNull Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public @NonNull Reason getReason() {
        return reason;
    }
}
