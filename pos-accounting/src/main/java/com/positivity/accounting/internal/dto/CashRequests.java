package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.config.IsoCurrencyCodes;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import org.jspecify.annotations.Nullable;

/** The request rules the float and petty-expense category commands share (#2511). */
public final class CashRequests {

    /** Shortest justification accepted, after trimming. */
    public static final int MIN_JUSTIFICATION = 10;

    /** Longest justification accepted. */
    public static final int MAX_JUSTIFICATION = 1000;

    private CashRequests() {}

    /**
     * A justification of {@value #MIN_JUSTIFICATION} to {@value #MAX_JUSTIFICATION} characters.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR}) otherwise
     */
    public static void requireJustification(@Nullable String value, String field) {
        if (value == null || value.trim().length() < MIN_JUSTIFICATION) {
            throw new InvalidRequestParameterException(
                    field + " is required and must be at least " + MIN_JUSTIFICATION + " characters");
        }
        if (value.length() > MAX_JUSTIFICATION) {
            throw new InvalidRequestParameterException(field + " must not exceed " + MAX_JUSTIFICATION + " characters");
        }
    }

    /**
     * An ISO 4217 code (ADR-0067 R-3, PC-4), checked against the module's one list ({@link IsoCurrencyCodes}, the
     * JDK's). A code is never normalised: {@code usd} is refused.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR} naming {@code field}) otherwise
     */
    public static void requireCurrencyCode(@Nullable String value, String field) {
        if (!IsoCurrencyCodes.isIso(value)) {
            throw InvalidRequestParameterException.forField(
                    field, field + " is required and must be an ISO 4217 currency code");
        }
    }
}
