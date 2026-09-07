package com.positivity.price.internal.exception;

/** A labor rate or matrix step the caller described cannot be stored as asked (#1575 Tier 0). */
public class LaborRateValidationException extends RuntimeException {

    public LaborRateValidationException(String message) {
        super(message);
    }
}
