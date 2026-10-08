package com.positivity.tax.internal.exception;

import com.positivity.shared.error.ApiError;
import java.util.List;

/**
 * A stated tax amount that cannot be right for the receipt it is stated on (CAP:550 S32b, AW55): it
 * reaches the receipt total, the stated amounts together reach it, or one amount is above its regime's
 * plausible maximum.
 * <p>
 * Maps to {@code 422 TAX_AMOUNT_IMPLAUSIBLE}, the only refusal for an over-stated amount. Each field error
 * names the offending amount and carries its maximum.
 */
public class TaxAmountImplausibleException extends RuntimeException {

    private final transient List<ApiError.FieldError> fieldErrors;

    public TaxAmountImplausibleException(List<ApiError.FieldError> fieldErrors) {
        super("A stated tax amount is implausible for the receipt total");
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<ApiError.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
