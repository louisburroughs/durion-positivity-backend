package com.positivity.order.internal.config;

import com.neovisionaries.i18n.CurrencyCode;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The functional currency pos-order's drawer money is in (ADR-0067 R-1, R-6; CAP:550 S16, #2512): the
 * currency of every cash movement, approval, drawer-policy limit and close fact. Configured as {@code
 * pos.order.functional-currency} (USD by default, as pos-accounting's {@code
 * accounting.ledger.base-currency}); a value that is not an ISO 4217 code fails startup.
 */
@Component
public class FunctionalCurrency {

    private final String code;

    public FunctionalCurrency(@Value("${pos.order.functional-currency:USD}") @NonNull String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!isIsoCode(normalized)) {
            throw new IllegalStateException(
                    "pos.order.functional-currency must be an ISO 4217 code, was '" + code + "'");
        }
        this.code = normalized;
    }

    /** The functional currency's ISO 4217 code, upper case. */
    public @NonNull String code() {
        return code;
    }

    /** Whether {@code value} (as sent) is the functional currency. */
    public boolean isFunctional(@Nullable String value) {
        return value != null && code.equals(value.trim());
    }

    /** Whether {@code value} is an ISO 4217 code (the same list {@code @IsoCurrencyCode} checks). */
    public static boolean isIsoCode(@Nullable String value) {
        return value != null && !value.isBlank() && CurrencyCode.getByCode(value.trim()) != null;
    }
}
