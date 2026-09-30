package com.positivity.inventory.internal.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class ReturnQuantityExceededException extends RuntimeException {

    /**
     * @param itemId the product id of the line when one is known, else the workorder line id the
     *     caller named (a consumed line whose replica row and ledger row carry no product); the
     *     message labels it as the item so neither reading is wrong
     */
    public ReturnQuantityExceededException(UUID itemId, BigDecimal requested, BigDecimal available) {
        super("Return quantity exceeds available consumed quantity for item "
                + itemId
                + ": requested="
                + requested
                + ", available="
                + available);
    }
}
