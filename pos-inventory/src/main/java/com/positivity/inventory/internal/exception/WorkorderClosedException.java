package com.positivity.inventory.internal.exception;

public class WorkorderClosedException extends RuntimeException {
    public WorkorderClosedException(String message) {
        super(message);
    }
}