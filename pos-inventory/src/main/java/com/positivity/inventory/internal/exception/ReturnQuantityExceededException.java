package com.positivity.inventory.internal.exception;

import java.util.UUID;

public class ReturnQuantityExceededException extends RuntimeException {

    public ReturnQuantityExceededException(UUID skuId, int requested, int available) {
        super("Return quantity exceeds available consumed quantity for skuId "
                + skuId
                + ": requested="
                + requested
                + ", available="
                + available);
    }
}
