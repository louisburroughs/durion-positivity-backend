package com.positivity.accounting.internal.exception;

import com.positivity.shared.error.ApiError;
import org.jspecify.annotations.NonNull;

/**
 * pos-tax refused a tax-registration write with 400, 404, 409 or 422 (CAP:550 S32c; AW59): the front door relays its
 * status and its error envelope's code, message and field errors, {@code TAX_REGISTRATION_OVERLAP}, {@code
 * IDEMPOTENCY_CONFLICT}, {@code TAX_REGIME_NOT_DECLARED} and {@code VALIDATION_ERROR} with {@code
 * fieldErrors[registrationNumber]} included. pos-tax never puts a number in that envelope.
 */
public class TaxRegistrationRelayException extends RuntimeException {

    private final int status;
    private final transient ApiError error;

    public TaxRegistrationRelayException(int status, @NonNull ApiError error) {
        super("pos-tax refused the tax-registration write: " + status + " " + error.code());
        this.status = status;
        this.error = error;
    }

    public int getStatus() {
        return status;
    }

    public @NonNull ApiError getError() {
        return error;
    }
}
