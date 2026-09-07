package com.positivity.price.internal.controller;

import com.positivity.price.internal.exception.LaborRateValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Turns the text a bulk-ingest record carries into the typed values the authoring DTOs expect.
 *
 * <p>Ingest records are all-text on purpose: the loader reads its files as strings, and a value
 * that will not parse must fail its own row rather than reject the batch at deserialisation.
 * Every failure is a {@link LaborRateValidationException}, which the bulk controllers list among
 * their row rejections, so a bad value is reported against its row with the field named.
 */
final class PriceIngestValues {

    private PriceIngestValues() {}

    @Nullable
    static UUID uuid(@Nullable String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            throw new LaborRateValidationException(field + " must be a UUID: " + value);
        }
    }

    @Nullable
    static BigDecimal decimal(@Nullable String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            throw new LaborRateValidationException(field + " must be a decimal number: " + value);
        }
    }

    @Nullable
    static Integer integer(@Nullable String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            throw new LaborRateValidationException(field + " must be a whole number: " + value);
        }
    }

    @Nullable
    static Instant instant(@Nullable String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new LaborRateValidationException(field + " must be an ISO-8601 instant: " + value);
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
