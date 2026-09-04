package com.positivity.accounting.internal.exception;

/**
 * General-purpose exception for malformed or invalid request fields/parameters
 * that do not warrant a more specific type: required-field checks, unparseable
 * identifiers, out-of-range simple values, and similar request-shape problems.
 * Maps to HTTP 400 (VALIDATION_ERROR) per ADR-0017 §1.
 */
public class InvalidRequestParameterException extends RuntimeException {

    public InvalidRequestParameterException(String message) {
        super(message);
    }

    public InvalidRequestParameterException(String message, Throwable cause) {
        super(message, cause);
    }
}
