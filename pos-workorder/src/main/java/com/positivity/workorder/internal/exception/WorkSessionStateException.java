package com.positivity.workorder.internal.exception;

/**
 * Thrown when a work-session operation is invalid for the current session
 * state.
 */
public class WorkSessionStateException extends RuntimeException {

    public WorkSessionStateException(String message) {
        super(message);
    }
}
