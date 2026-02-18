package com.positivity.location.internal.exception;

public class DuplicateLocationCodeException extends RuntimeException {
    public DuplicateLocationCodeException(String code) {
        super("Location code already exists: " + code);
    }
}
