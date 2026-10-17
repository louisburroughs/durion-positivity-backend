package com.positivity.tenant.internal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A reading tier with the code already exists. (#2619; answered as {@code READING_TIER_EXISTS}). */
@ResponseStatus(HttpStatus.CONFLICT)
public class ReadingTierExistsException extends RuntimeException {

    public static final String CODE = "READING_TIER_EXISTS";

    public ReadingTierExistsException(String message) {
        super(message);
    }
}
