package com.positivity.catalog.internal.exception;

public class CatalogForbiddenOperationException extends RuntimeException {

    public CatalogForbiddenOperationException(String message) {
        super(message);
    }
}
