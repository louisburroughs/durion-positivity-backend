package com.positivity.people.internal.exception;

public class DuplicateLocationCodeException extends RuntimeException {

    public DuplicateLocationCodeException(String code) {
        super("Location already exists with code: " + code);
    }
}
