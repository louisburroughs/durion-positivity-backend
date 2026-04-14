package com.positivity.inventory.internal.exception;

public class OverReceiptNotPermittedException extends RuntimeException {

    public OverReceiptNotPermittedException(String message) {
        super(message);
    }
}
