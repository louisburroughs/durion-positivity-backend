package com.positivity.order.internal.exception;

import org.jspecify.annotations.NonNull;

/**
 * A return against a walk-in sale (the original order's customer is the tenant's CASH house
 * account) asked for a refund to store credit or on-account credit (CAP:550 S8, decision AW12).
 * The house account never carries a balance, so only {@code ORIGINAL_TENDER} is allowed. Refused
 * at creation, before the saga starts. Maps to {@code 422 RETURN_WALK_IN_NOT_ALLOWED}.
 */
public class ReturnWalkInNotAllowedException extends RuntimeException {

    public ReturnWalkInNotAllowedException(@NonNull String refundMethod) {
        super("A walk-in sale cannot be refunded as " + refundMethod + "; refund to the original tender");
    }
}
