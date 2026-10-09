package com.positivity.order.internal.exception;

import com.positivity.shared.error.ApiError;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A petty expense's stated tax or amount refused by a rule the request's shape cannot show (CAP:550 S32d items 5 and
 * 6; ADR-0017 §2): answered 422 with its {@link Code} and {@code fieldErrors} naming each offending field. Nothing is
 * recorded and an approval token stays unspent. The message never carries the supplier's name or number.
 */
public class CashMovementTaxRefusedException extends RuntimeException {

    /** Each refusal's wire code; every one answers 422. */
    public enum Code {
        /** An amount finer than the drawer currency's minor unit (ADR-0067 PC-6), never rounded. */
        AMOUNT_PRECISION_EXCEEDS_CURRENCY,
        /** A stated amount at or above the receipt total, or a sum at or above it, or pos-tax's own bound. */
        TAX_AMOUNT_IMPLAUSIBLE,
        /** A regime the drawer does not offer for this category, location, currency and date. */
        TAX_REGIME_NOT_OFFERED,
        /** A supplier's number for a country that names no supplier regime: never recorded unchecked. */
        SUPPLIER_REGISTRATION_NOT_ACCEPTED
    }

    private final transient Code code;
    private final transient List<ApiError.FieldError> fieldErrors;

    public CashMovementTaxRefusedException(
            @NonNull Code code, @NonNull String message, @NonNull List<ApiError.FieldError> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public @NonNull Code code() {
        return code;
    }

    public @NonNull List<ApiError.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
