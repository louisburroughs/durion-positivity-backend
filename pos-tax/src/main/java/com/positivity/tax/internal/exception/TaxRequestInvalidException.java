package com.positivity.tax.internal.exception;

import com.positivity.shared.error.ApiError;
import java.util.List;

/**
 * A request whose shape is invalid in a way bean validation cannot see (CAP:550 S32b): today, a regime
 * stated more than once on a plausibility check.
 * <p>
 * Maps to {@code 400 VALIDATION_ERROR} with {@code fieldErrors}. A field error names the field and the rule,
 * never the rejected value.
 */
public class TaxRequestInvalidException extends RuntimeException {

    private final transient List<ApiError.FieldError> fieldErrors;

    public TaxRequestInvalidException(List<ApiError.FieldError> fieldErrors) {
        super("Request validation failed");
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<ApiError.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
