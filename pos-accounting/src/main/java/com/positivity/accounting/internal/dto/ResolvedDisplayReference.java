package com.positivity.accounting.internal.dto;

import org.jspecify.annotations.Nullable;

/**
 * The human-readable identity accounting can offer for one reference — a UUID-backed id or a
 * code-keyed location code (issues #1778, #1779, #1797).
 *
 * <p>Both components are independently nullable, and both are null when nothing is known. A
 * caller renders whichever it has and shows nothing when it has neither — the raw identifier is
 * never a fallback display value.
 *
 * @param displayName      human-readable name, e.g. a customer's display name or a location
 *                         label; null when the source holds no name
 * @param displayReference stable business reference or number, e.g. an invoice number, customer
 *                         number or journal-entry number; null when the source holds none
 */
public record ResolvedDisplayReference(
        @Nullable String displayName, @Nullable String displayReference) {

    /** Nothing is known about this reference: both display values are absent. */
    public static final ResolvedDisplayReference EMPTY = new ResolvedDisplayReference(null, null);

    /** A reference known only by its business number (invoices, journal entries, vendor bills). */
    public static ResolvedDisplayReference ofReference(@Nullable String displayReference) {
        return new ResolvedDisplayReference(null, displayReference);
    }

    /** True when neither display value is present, so there is nothing for a caller to render. */
    public boolean isEmpty() {
        return isBlank(displayName) && isBlank(displayReference);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
