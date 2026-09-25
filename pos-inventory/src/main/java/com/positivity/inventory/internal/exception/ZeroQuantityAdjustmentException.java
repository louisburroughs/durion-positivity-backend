package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when a manual adjustment request with a zero quantity is approved (issue #2201). Such a
 * request moves nothing: it would post an empty ledger row and no accounting fact. Create-time
 * validation rejects a zero quantity, so this guards requests stored before that check existed;
 * the approval marks the request {@code REJECTED} before throwing.
 *
 * <p>Maps to a deterministic 422 with code {@link #ERROR_CODE}.
 */
public class ZeroQuantityAdjustmentException extends RuntimeException {

    /** Deterministic error code for the ApiError envelope. */
    public static final String ERROR_CODE = "ADJUSTMENT_QUANTITY_ZERO";

    public ZeroQuantityAdjustmentException(UUID adjustmentRequestId) {
        super("Adjustment request " + adjustmentRequestId
                + " has a zero quantity and moves no stock; it has been rejected instead of posted");
    }
}
