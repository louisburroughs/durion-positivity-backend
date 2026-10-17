package com.positivity.workorder.internal.config;

import java.util.Currency;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The tenant's functional currency as pos-workorder states it on every tax request (ADR-0067 R-2 / PC-2; #2644).
 *
 * <p>Read from {@code pos.workorder.functional-currency} ({@code POS_WORKORDER_FUNCTIONAL_CURRENCY}): the interim
 * per-module source pos-order and pos-invoice also use until the tenant carries its currency (#2583). Defaults to
 * USD; a value that is not an ISO 4217 code refuses startup.
 */
@Component
public class FunctionalCurrency {

    private final String code;

    public FunctionalCurrency(@Value("${pos.workorder.functional-currency:USD}") @NonNull String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException notIso) {
            throw new IllegalStateException(
                    "pos.workorder.functional-currency (POS_WORKORDER_FUNCTIONAL_CURRENCY) must be an ISO 4217 code,"
                            + " was '" + code + "'",
                    notIso);
        }
        this.code = normalized;
    }

    public @NonNull String code() {
        return code;
    }
}
