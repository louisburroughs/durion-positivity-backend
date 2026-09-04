package com.positivity.workorder.internal.exception;

import java.util.UUID;

/** No line item exists for the requested id on the named estimate (issue #1694). */
public class EstimateItemNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "ESTIMATE_ITEM_NOT_FOUND";

    public EstimateItemNotFoundException(UUID itemId, UUID estimateId) {
        super("Item not found: " + itemId + " for estimate: " + estimateId);
    }
}
