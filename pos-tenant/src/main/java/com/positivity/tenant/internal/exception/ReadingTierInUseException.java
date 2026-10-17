package com.positivity.tenant.internal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The tier cannot be deactivated while a non-decommissioned tenant is on it. (#2619; answered as {@code READING_TIER_IN_USE}). */
@ResponseStatus(HttpStatus.CONFLICT)
public class ReadingTierInUseException extends RuntimeException {

    public static final String CODE = "READING_TIER_IN_USE";

    public ReadingTierInUseException(String message) {
        super(message);
    }
}
