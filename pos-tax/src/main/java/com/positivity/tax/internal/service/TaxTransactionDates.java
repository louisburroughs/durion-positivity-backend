package com.positivity.tax.internal.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the transaction date used for effective-dated rate selection; shared by the test-mode
 * calculator and the per-country plug-in so both read {@code transactionDate} the same way.
 */
final class TaxTransactionDates {

    private TaxTransactionDates() {}

    /**
     * When {@code raw} is {@code null} or blank, the current date is used, derived from the shared
     * {@link Clock}. Otherwise the value is parsed as an ISO-8601 date ({@code 2026-02-21}) or
     * date-time ({@code 2026-02-21T09:30:00Z}); the calendar date is used as written, without
     * timezone conversion.
     *
     * @param raw   the optional ISO-8601 transaction date string from the request
     * @param clock the shared clock
     * @return the resolved transaction date
     * @throws IllegalArgumentException if {@code raw} is present but not valid ISO-8601
     */
    @NonNull
    static LocalDate resolve(@Nullable String raw, @NonNull Clock clock) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now(clock);
        }
        String value = raw.trim();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException dateOnly) {
            try {
                return LocalDate.from(DateTimeFormatter.ISO_DATE_TIME.parse(value));
            } catch (DateTimeParseException dateTime) {
                throw new IllegalArgumentException(
                        "transactionDate must be a valid ISO-8601 date or date-time: " + value);
            }
        }
    }
}
