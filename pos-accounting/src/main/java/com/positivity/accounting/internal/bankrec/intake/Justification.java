package com.positivity.accounting.internal.bankrec.intake;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The justification rule of the bank reconciliation core (SPEC §4 preamble, §4.10, D15; story S2,
 * #2301), one condition one code: absent or blank → 400 {@code VALIDATION_ERROR}; 1–9 characters →
 * 400 {@code JUSTIFICATION_REQUIRED}. It covers the gap acknowledgement, duplicate review, exclude
 * and restore.
 */
public final class Justification {

    /** Justification minimum (D15). */
    public static final int MIN_LENGTH = 10;

    private Justification() {}

    /** A justification the operation requires; returns it trimmed. */
    public static @NonNull String required(@Nullable String value, @NonNull String field) {
        String checked = optional(value, field);
        if (checked == null) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, field + " is required", field, "is required");
        }
        return checked;
    }

    /**
     * A justification a rule requires in this case (an {@code OTHER} adjustment, a timing item, a reaffirmation;
     * story S4, #2303): absent, blank or 1–9 characters is one condition — 400 {@code JUSTIFICATION_REQUIRED}.
     * Returns it trimmed.
     */
    public static @NonNull String requiredByRule(@Nullable String value, @NonNull String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() < MIN_LENGTH) {
            throw BankRecException.field(
                    BankRecErrorCode.JUSTIFICATION_REQUIRED,
                    field + " of at least " + MIN_LENGTH + " characters is required",
                    field,
                    "at least " + MIN_LENGTH + " characters");
        }
        return trimmed;
    }

    /** A justification that may be absent (the gap acknowledgement); a present one is checked in full. */
    public static @Nullable String optional(@Nullable String value, @NonNull String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, field + " must not be blank", field, "must not be blank");
        }
        if (trimmed.length() < MIN_LENGTH) {
            throw BankRecException.field(
                    BankRecErrorCode.JUSTIFICATION_REQUIRED,
                    field + " must be at least " + MIN_LENGTH + " characters",
                    field,
                    "at least " + MIN_LENGTH + " characters");
        }
        return trimmed;
    }
}
