package com.positivity.tax.internal.exception;

import org.jspecify.annotations.NonNull;

/**
 * A tax-registration write that conflicts with the registry's state (CAP:550 S32c): 409 with the code. Two codes
 * exist: {@link #OVERLAP}, another registration of the same tenant, country and regime is in effect on a date the
 * write covers, and {@link #OPTIMISTIC_LOCK}, the version the caller read is no longer current. The message never
 * carries a registration number.
 */
public class TaxRegistrationConflictException extends RuntimeException {

    public static final String OVERLAP = "TAX_REGISTRATION_OVERLAP";
    public static final String OPTIMISTIC_LOCK = "OPTIMISTIC_LOCK";

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

    public static TaxRegistrationConflictException optimisticLock() {
        return new TaxRegistrationConflictException(
                OPTIMISTIC_LOCK, "The registration was changed since it was read; read it again and retry");
    }
}
