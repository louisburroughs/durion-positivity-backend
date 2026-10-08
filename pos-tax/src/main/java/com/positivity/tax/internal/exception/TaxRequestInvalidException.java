package com.positivity.tax.internal.exception;

import com.positivity.shared.error.ApiError;
import java.util.List;

/**
 * A request whose shape is valid but whose values do not fit the configuration it names (CAP:550
 * S32b): a country without a profile, a currency other than the profile's, an amount with more
 * decimals than the currency allows, or a regime the country does not declare or that is stated twice.
 * <p>
 * Maps to {@code 400 VALIDATION_ERROR} with {@code fieldErrors}. A field error names the field and the
 * rule, never the rejected value.
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
