package com.positivity.accounting.internal.dto;

/**
 * Thrown when a duplicate accounting event submission is detected.
 */
public class DuplicateEventException extends RuntimeException {

    public DuplicateEventException(String message) {
        super(message);
    }
}
