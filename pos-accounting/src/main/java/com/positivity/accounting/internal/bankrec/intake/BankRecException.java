package com.positivity.accounting.internal.bankrec.intake;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * A refusal of the bank reconciliation core (SPEC §4.10; story S2, #2301), answered by the module's
 * exception handler as an ADR-0017 {@code ApiError} with {@link #code()}, its status and, when
 * present, {@link #fieldErrors()} (for example {@code fieldErrors[openingBalance] = "expected
 * 12345.67"}).
 */
public class BankRecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final BankRecErrorCode code;

    @SuppressWarnings("serial") // an immutable LinkedHashMap copy; never serialized in practice
    private final Map<String, String> fieldErrors;

    public BankRecException(@NonNull BankRecErrorCode code, @NonNull String message) {
        this(code, message, Map.of());
    }

    public BankRecException(
            @NonNull BankRecErrorCode code, @NonNull String message, @NonNull Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    /** A refusal naming one field. */
    public static BankRecException field(
            @NonNull BankRecErrorCode code, @NonNull String message, @NonNull String field, @NonNull String detail) {
        return new BankRecException(code, message, Map.of(field, detail));
    }

    public @NonNull BankRecErrorCode code() {
        return code;
    }

    /** Field name → detail, in insertion order; empty when the refusal names no field. */
    public @NonNull Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
