package com.positivity.order.internal.exception;

import java.util.UUID;

/**
 * A WARRANTY-condition return was requested on a non-returnable workorder-consumed line (parity
 * story F1, resolved Q6). Counter returns cannot settle warranty claims — these route to
 * pos-warranty. Maps to a 422 {@code RETURN_WARRANTY_ROUTING} ApiError pointing at pos-warranty.
 */
public class WarrantyReturnRoutingException extends RuntimeException {

    public WarrantyReturnRoutingException(UUID orderLineId) {
        super("Line " + orderLineId + " is a non-returnable workorder-consumed line; warranty claims"
                + " are handled by pos-warranty, not a counter return");
    }
}
