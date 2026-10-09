package com.positivity.tax.internal.exception;

import com.positivity.shared.error.ApiError;
import java.util.List;

/**
 * A well-formed request that the referenced configuration or currency refuses (CAP:550 S32b; ADR-0017
 * §2, ADR-0067 PC-6 and PC-9): 422 with the given code and field errors.
 * <p>
 * The codes in use are {@link #JURISDICTION_NOT_CONFIGURED} (the country has no tax profile),
 * {@link #CURRENCY_NOT_SUPPORTED} (another currency than the profile's), {@link #PRECISION_EXCEEDS_CURRENCY}
 * (an amount finer than the currency's minor unit) and {@link #REGIME_NOT_DECLARED} (a regime the country
 * does not declare). A field error names the field and the rule, never the rejected value.
 */
public class TaxRequestUnprocessableException extends RuntimeException {

    /** The country has no tax profile (the code S32a's {@code /calculate} uses). */
    public static final String JURISDICTION_NOT_CONFIGURED = "TAX_JURISDICTION_NOT_CONFIGURED";

    /** The request's currency is not the country profile's (ADR-0067 PC-9). */
    public static final String CURRENCY_NOT_SUPPORTED = "CURRENCY_NOT_SUPPORTED";

    /** An amount has more decimals than the currency's minor unit allows (ADR-0067 PC-6). */
    public static final String PRECISION_EXCEEDS_CURRENCY = "AMOUNT_PRECISION_EXCEEDS_CURRENCY";

    /** A stated regime is not declared for the country. */
    public static final String REGIME_NOT_DECLARED = "TAX_REGIME_NOT_DECLARED";

    private final String code;
    private final transient List<ApiError.FieldError> fieldErrors;

    public TaxRequestUnprocessableException(String code, String message, List<ApiError.FieldError> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public String getCode() {
        return code;
    }

    public List<ApiError.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
