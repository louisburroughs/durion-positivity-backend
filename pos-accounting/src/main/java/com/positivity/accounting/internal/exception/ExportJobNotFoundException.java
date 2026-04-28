package com.positivity.accounting.internal.exception;

public class ExportJobNotFoundException extends RuntimeException {
    public ExportJobNotFoundException(String message) {
        super(message);
    }
}
