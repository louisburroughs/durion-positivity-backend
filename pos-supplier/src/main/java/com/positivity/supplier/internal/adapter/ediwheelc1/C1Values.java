package com.positivity.supplier.internal.adapter.ediwheelc1;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Scalar parsing at the C1 wire boundary.
 *
 * <h2>Absence is not zero, and an unreadable value is not absent</h2>
 *
 * Every method here answers null for "the vendor stated nothing", which is the same rule the
 * stock report enforces (#1228) and the reason nothing downstream defaults a quantity or a date.
 * A value the vendor <em>did</em> state but we cannot read is a different problem: it also
 * decodes to null, but the codec records it as an unmapped field so the discrepancy is
 * diagnosable rather than silently identical to silence.
 *
 * <p>The distinction matters most for dates. A despatch date we failed to parse and a despatch
 * with no date look the same on a timeline, and only the unmapped-field record tells an operator
 * which one they are looking at.
 */
final class C1Values {

    /**
     * Date formats seen across the C1 family. The spec declares {@code format: date} (ISO), but
     * the sibling B-series norms state dates as {@code yyyyMMdd} and vendors reuse their
     * formatters, so both are accepted rather than losing a delivery date to a format quibble.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS =
            List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("yyyyMMdd"));

    private C1Values() {}

    /**
     * Parses a quantity element.
     *
     * @return the whole quantity, or null when the element is absent or states nothing
     */
    @Nullable
    static Integer quantity(OrderC1Wire.@Nullable Quantity quantity) {
        if (quantity == null) {
            return null;
        }
        String value = trimToNull(quantity.quantityValue);
        if (value == null) {
            return null;
        }
        try {
            // Some markets state whole quantities with a decimal part ("4.000"); take the integral
            // part rather than discarding a line that is well formed for its market.
            return new BigDecimal(value.replace(',', '.')).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** Whether a stated quantity could not be read — the caller records it as unmapped. */
    static boolean isUnreadableQuantity(OrderC1Wire.@Nullable Quantity quantity) {
        return quantity != null && trimToNull(quantity.quantityValue) != null && quantity(quantity) == null;
    }

    /**
     * Parses a date element.
     *
     * @return the date, or null when the value is absent or unreadable
     */
    @Nullable
    static LocalDate date(@Nullable String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (Exception ignored) {
                // Try the next format; the caller records an unreadable value as unmapped.
            }
        }
        return null;
    }

    /** Whether a stated date could not be read — the caller records it as unmapped. */
    static boolean isUnreadableDate(@Nullable String raw) {
        return trimToNull(raw) != null && date(raw) == null;
    }

    @Nullable
    static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
