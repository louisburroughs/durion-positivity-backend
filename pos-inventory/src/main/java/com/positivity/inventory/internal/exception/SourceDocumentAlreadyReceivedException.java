package com.positivity.inventory.internal.exception;

public class SourceDocumentAlreadyReceivedException extends RuntimeException {
    public SourceDocumentAlreadyReceivedException(String message) {
        super(message);
    }
}
