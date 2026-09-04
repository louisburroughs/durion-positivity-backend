package com.positivity.workorder.internal.exception;

/**
 * An estimate approval is semantically valid but the approving customer's billing rules
 * (CAP:092 Story #98) require a purchase order number that was not supplied.
 *
 * <p>Answered as {@code 422} (ADR-0017 §2): the payload is well-formed and the missing field is
 * not itself invalid — whether a PO is required at all is a documented domain policy resolved by
 * a billing-rules lookup, not something request-shape validation could ever determine.
 */
public class PurchaseOrderRequiredException extends RuntimeException {

    public static final String ERROR_CODE = "PURCHASE_ORDER_REQUIRED";

    public PurchaseOrderRequiredException(String message) {
        super(message);
    }
}
