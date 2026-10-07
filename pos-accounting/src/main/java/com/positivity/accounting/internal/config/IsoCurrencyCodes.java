package com.positivity.accounting.internal.config;

import java.util.Currency;
import org.jspecify.annotations.Nullable;

/**
 * The module's one ISO 4217 check (ADR-0067 R-3, PC-4): a code is valid when the JDK's {@link Currency} list knows
 * it, the list the minor-unit rule reads too. Codes are upper case; this check does not normalise.
 */
public final class IsoCurrencyCodes {

    private IsoCurrencyCodes() {}

    /** Whether {@code code} is a three-letter ISO 4217 currency code. */
    public static boolean isIso(@Nullable String code) {
        if (code == null || code.length() != 3) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
