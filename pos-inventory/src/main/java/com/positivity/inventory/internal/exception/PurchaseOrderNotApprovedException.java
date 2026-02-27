package com.positivity.inventory.internal.exception;

public class PurchaseOrderNotApprovedException extends RuntimeException {

    public PurchaseOrderNotApprovedException(String message) {
        super(message);
    }
}
