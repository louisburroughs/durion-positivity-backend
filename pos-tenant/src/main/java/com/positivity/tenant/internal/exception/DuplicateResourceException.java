package com.positivity.tenant.internal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A slug or legal name that is already taken. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateResourceException extends IllegalStateException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
