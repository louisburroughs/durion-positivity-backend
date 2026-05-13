package com.positivity.inventory.internal.exception;

/**
 * Raised when mutually dependent query parameters are provided in an invalid
 * combination.
 */
public class InvalidParamCombinationException extends RuntimeException {

    public InvalidParamCombinationException(String message) {
        super(message);
    }
}
