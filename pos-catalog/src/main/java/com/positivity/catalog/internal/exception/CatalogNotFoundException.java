package com.positivity.catalog.internal.exception;

public class CatalogNotFoundException extends RuntimeException {

    public CatalogNotFoundException(String message) {
        super(message);
    }
}
