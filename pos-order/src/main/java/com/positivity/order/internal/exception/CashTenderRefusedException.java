package com.positivity.order.internal.exception;

import com.positivity.shared.error.ApiError;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A register cash tender pos-order refuses before anything is recorded (ADR-0067 A8, #2662; ADR-0017 §2): answered
 * 422 with its {@link Code} and {@code fieldErrors} naming the offending field and the figure it was measured
 * against. Nothing is recorded and no partial capture is taken from an insufficient tender.
 */
public class CashTenderRefusedException extends RuntimeException {

    public enum Code {
        /** {@code cashTendered} is below the rounded cash due. */
        CASH_TENDER_INSUFFICIENT,
        /** A partial {@code cashApplied} is not a whole multiple of the currency's cash increment. */
        CASH_AMOUNT_NOT_IN_INCREMENT,
        /** The order has no register session: no drawer to hold the cash. */
        CASH_TENDER_SESSION_REQUIRED
    }

    private final Code code;
    private final transient List<ApiError.FieldError> fieldErrors;

    public CashTenderRefusedException(
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
