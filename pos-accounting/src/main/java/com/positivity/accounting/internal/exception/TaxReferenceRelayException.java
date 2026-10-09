package com.positivity.accounting.internal.exception;

import com.positivity.shared.error.ApiError;
import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * pos-tax refused a reference read with a 4xx (CAP:550 #2615, {@code TaxReferenceClient}): its status, code, message
 * and field errors are relayed unchanged to the caller of the front door (ADR-0071, AW59), with this request's
 * correlation id. S32c's {@code TaxRegistrationRelayException} does the same for registration writes.
 */
public class TaxReferenceRelayException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient ApiError error;

    public TaxReferenceRelayException(int status, @NonNull ApiError error) {
        super("pos-tax answered " + status + " " + error.code());
        this.status = status;
        this.error = error;
    }

    /** pos-tax's HTTP status. */
    public int getStatus() {
        return status;
    }

    /** pos-tax's error envelope. */
    public @NonNull ApiError getError() {
        return error;
    }
}
