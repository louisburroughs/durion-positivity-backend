package com.positivity.supplier.internal.adapter.ediwheela2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Scalar parsing at the A2 wire boundary.
 *
 * <h2>Absence is not zero</h2>
 *
 * Every method here answers null for "the vendor stated nothing", the same rule the stock report
 * (#1228) and the C1 family enforce. For an availability answer the distinction is the whole point:
 * a vendor that did not answer and a vendor that answered "none" produce very different sentences
 * on a product page, and coercing the first into {@code 0} would put "out of stock" in front of a
 * customer on the strength of a timeout.
 */
final class A2Values {

    /**
     * Date formats seen across the EDIWheel norms. The spec declares {@code format: date} (ISO),
     * but sibling norms state dates as {@code yyyyMMdd} and vendors reuse their formatters, so both
     * are accepted rather than losing a delivery date to a format quibble.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS =
            List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("yyyyMMdd"));

    private A2Values() {}

    /**
     * Parses a quantity element.
     *
     * @return the whole quantity, or null when the element is absent or states nothing
     */
    @Nullable
    static Integer quantity(StockInquiryA2Wire.@Nullable Quantity quantity) {
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
    static boolean isUnreadableQuantity(StockInquiryA2Wire.@Nullable Quantity quantity) {
        return quantity != null && trimToNull(quantity.quantityValue) != null && quantity(quantity) == null;
    }

    /**
     * Parses a date element.
     *
     * @return the date, or null when absent or unreadable
     */
    @Nullable
    static LocalDate date(@Nullable String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (RuntimeException e) {
                // Try the next format; an unreadable date is reported by the caller as unmapped.
            }
        }
        return null;
    }

    /** Whether a stated date could not be read. */
    static boolean isUnreadableDate(@Nullable String value) {
        return trimToNull(value) != null && date(value) == null;
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
