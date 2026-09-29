package com.positivity.domainevents.bankfeed;

import java.util.Currency;
import org.jspecify.annotations.Nullable;

/**
 * Currency-code validation for the bank-feed contract: a code is checked against the ISO 4217 list
 * the JDK carries, not against a pattern (ADR-0067 R-3). Package-private — the contract exposes
 * codes as plain {@code String}s.
 */
final class IsoCurrency {

    private IsoCurrency() {}

    /**
     * Returns {@code code} unchanged when it is a known ISO 4217 alphabetic code, else throws.
     *
     * @param field the component name, for the message
     * @param code the candidate code
     * @return the code
     * @throws IllegalArgumentException when the code is null or not an ISO 4217 code
     */
    static String require(String field, @Nullable String code) {
        if (code == null || code.length() != 3 || !code.equals(code.toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be an ISO 4217 code (e.g. USD) but was: " + code);
        }
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(field + " is not an ISO 4217 currency code: " + code, unknown);
        }
        return code;
    }
}
