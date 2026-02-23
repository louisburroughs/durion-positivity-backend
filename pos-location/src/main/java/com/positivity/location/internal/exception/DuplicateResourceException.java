package com.positivity.location.internal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception for duplicate-resource conflicts.
 *
 * Issue: #76
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateResourceException extends IllegalStateException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
