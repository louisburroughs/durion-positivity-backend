package com.positivity.order.internal.exception;

/**
 * A price-override request is malformed on its face: a search with no filter parameter, or an
 * {@code orderId}/{@code orderLineId}/{@code productId} that is not a well-formed UUID, or a
 * {@code reasonCode} that does not name a known
 * {@link com.positivity.order.internal.entity.PriceOverrideReasonCode} (issue #1694). Distinct
 * from {@link InvalidPriceOverrideException}, which is a well-formed request the domain refuses
 * on its merits (e.g. the order is not DRAFT). Maps to a 400 {@code ORDER_PRICE_OVERRIDE_BAD_REQUEST}
 * ApiError — the same code and status the blanket {@code IllegalArgumentException} handler this
 * type replaces already used.
 */
public class PriceOverrideRequestValidationException extends RuntimeException {

    public PriceOverrideRequestValidationException(String message) {
        super(message);
    }
}
