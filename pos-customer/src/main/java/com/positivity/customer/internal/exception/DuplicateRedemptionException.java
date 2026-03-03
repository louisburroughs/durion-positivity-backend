package com.positivity.customer.internal.exception;

import java.util.UUID;

public class DuplicateRedemptionException extends RuntimeException {

    public DuplicateRedemptionException(UUID promotionId, UUID workorderId) {
        super("Promotion " + promotionId + " already redeemed for workorder " + workorderId);
    }
}