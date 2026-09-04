package com.positivity.order.internal.exception;

import java.util.UUID;

/**
 * A requested return line is not returnable per policy (a non-WARRANTY condition against a
 * workorder-consumed line whose imported {@code returnable} flag is not explicitly {@code true} —
 * resolved Q6). The payload is well-formed and the line exists; the domain refuses it on its
 * merits, so this is a 422, not a 400 (issue #1694). A WARRANTY-condition request against the same
 * kind of line instead routes through {@link WarrantyReturnRoutingException}.
 *
 * <p>Split out of the former blanket {@code RETURN_INVALID_ARGUMENT} 422 catch-all into its own
 * code so a caller can distinguish "this line cannot be returned" from a malformed request.
 */
public class ReturnLineNotReturnableException extends RuntimeException {

    public ReturnLineNotReturnableException(UUID orderLineId) {
        super("Line " + orderLineId + " is not returnable");
    }
}
