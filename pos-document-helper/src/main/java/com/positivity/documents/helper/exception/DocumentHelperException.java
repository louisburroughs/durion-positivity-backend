package com.positivity.documents.helper.exception;

/**
 * Base exception for document helper operations.
 *
 * Issue: ADR-0020
 */
public class DocumentHelperException extends RuntimeException {

    public DocumentHelperException(String message) {
        super(message);
    }

    public DocumentHelperException(String message, Throwable cause) {
        super(message, cause);
    }
}
