package com.positivity.tenant.internal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The tier does not exist or is inactive, so no tenant can be put on it. (#2619; answered as {@code READING_TIER_NOT_ASSIGNABLE}). */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ReadingTierNotAssignableException extends RuntimeException {

    public static final String CODE = "READING_TIER_NOT_ASSIGNABLE";

    public ReadingTierNotAssignableException(String message) {
        super(message);
    }
}
