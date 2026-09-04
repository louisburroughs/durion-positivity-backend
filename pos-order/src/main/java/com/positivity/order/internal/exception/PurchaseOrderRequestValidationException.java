package com.positivity.order.internal.exception;

/**
 * A purchase-order line request is malformed on its face: no {@code quantity} and no document-UoM
 * pair to derive one from, a document-UoM pair supplied only half (one of
 * {@code documentUom}/{@code documentQuantity} without the other), or a non-positive
 * {@code documentQuantity} (issue #1694). Distinct from
 * {@link UomConversionUndefinedException} (a well-formed line the catalog cannot resolve to a
 * base unit) and from {@link PurchaseOrderNotTransmittableException} (a well-formed, resolvable
 * order the domain still refuses to send). Maps to a 400 {@code PURCHASE_ORDER_BAD_REQUEST}
 * ApiError — the same code and status the blanket {@code IllegalArgumentException} handler this
 * type replaces already used.
 */
public class PurchaseOrderRequestValidationException extends RuntimeException {

    public PurchaseOrderRequestValidationException(String message) {
        super(message);
    }
}
