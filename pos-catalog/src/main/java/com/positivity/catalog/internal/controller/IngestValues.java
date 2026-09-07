package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.exception.CatalogValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Turns the text a bulk-ingest record carries into the typed values the authoring DTOs expect.
 *
 * <p>Ingest records are all-text on purpose: the loader reads its files as strings, and a value
 * that will not parse must fail its own row rather than reject the batch at deserialisation. That
 * makes parsing the controller's job, and it must be the same parsing everywhere — a field that
 * accepts {@code TRUE} on one endpoint and refuses it on another is a file that loads into one
 * domain and not the next.
 *
 * <p>Every failure is a {@link CatalogValidationException}, which the bulk controllers list among
 * their row rejections, so a bad value is reported against its row with the field named.
 */
final class IngestValues {

    private IngestValues() {}

    @Nullable
    static UUID uuid(@Nullable String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            throw new CatalogValidationException(field + " must be a UUID: " + value);
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
            throw new CatalogValidationException(field + " must be a decimal number: " + value);
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
            throw new CatalogValidationException(field + " must be a whole number: " + value);
        }
    }

    @Nullable
    static LocalDate date(@Nullable String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new CatalogValidationException(field + " must be an ISO-8601 date: " + value);
        }
    }

    /**
     * Blank means "not stated", so the caller's own default applies; anything else must say true
     * or false outright. {@code Boolean.parseBoolean} is deliberately not used: it reads every
     * unrecognised word as false, so a typo would quietly deactivate a package.
     */
    @Nullable
    static Boolean bool(@Nullable String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        throw new CatalogValidationException("expected true or false: " + value);
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
