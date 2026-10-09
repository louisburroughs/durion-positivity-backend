package com.positivity.tax.internal.exception;

import org.jspecify.annotations.NonNull;

/**
 * A tax-registration write that conflicts with the registry's state (CAP:550 S32c): 409 with the code. Three codes
 * exist: {@link #OVERLAP}, another registration of the same tenant, country and regime is in effect on a date the
 * write covers; {@link #OPTIMISTIC_LOCK}, the version the caller read is no longer current; and {@link
 * #IDEMPOTENCY_CONFLICT}, the request id was already used for another request (ADR-0017 §2). The message never carries
 * a registration number.
 */
public class TaxRegistrationConflictException extends RuntimeException {

    public static final String OVERLAP = "TAX_REGISTRATION_OVERLAP";
    public static final String OPTIMISTIC_LOCK = "OPTIMISTIC_LOCK";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    private final String code;

    public TaxRegistrationConflictException(@NonNull String code, @NonNull String message) {
        super(message);
        this.code = code;
    }

    public @NonNull String getCode() {
        return code;
    }

    public static TaxRegistrationConflictException overlap() {
        return new TaxRegistrationConflictException(
                OVERLAP,
                "Another registration for this country and regime is in effect on a date this registration covers");
    }

    /**
     * The request id was already used for another operation, registration or body (ADR-0017 §2), or a request with the
     * same id is in flight.
     */
    public static TaxRegistrationConflictException idempotencyConflict() {
        return new TaxRegistrationConflictException(
                IDEMPOTENCY_CONFLICT,
                "This requestId was already used for another request; send a new requestId for a new request");
    }

    public static TaxRegistrationConflictException optimisticLock() {
        return new TaxRegistrationConflictException(
                OPTIMISTIC_LOCK, "The registration was changed since it was read; read it again and retry");
    }
}
