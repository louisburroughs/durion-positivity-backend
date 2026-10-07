package com.positivity.order.internal.config;

import com.neovisionaries.i18n.CurrencyCode;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The functional currency of drawer money: the one place pos-order reads it (ADR-0067 R-2, R-6; CAP:550
 * S16, #2512). It is a Stage A interim, the counterpart of pos-accounting's {@code LedgerCurrency}: ADR-0067
 * step A5 swaps it for the tenant's functional currency (the PC-2 accessor), and nothing else in the
 * module reads the property.
 *
 * <p>Read from {@code pos.order.functional-currency} ({@code POS_ORDER_FUNCTIONAL_CURRENCY}), which has no
 * default: an unset value fails startup, as does one that is not an ISO 4217 code (R-2, no implicit
 * {@code USD}). A drawer is stamped with this currency when it opens; every movement, approval and close
 * fact of that drawer then uses the stamp, never this property, so a configuration change cannot
 * re-denominate an open drawer. The drawer policy is stated in it when it is written.
 */
@Component
public class FunctionalCurrency {

    private final String code;

    public FunctionalCurrency(@Value("${pos.order.functional-currency}") @NonNull String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!isIsoCode(normalized)) {
            throw new IllegalStateException(
                    "pos.order.functional-currency (POS_ORDER_FUNCTIONAL_CURRENCY) must be an ISO 4217 code, was '"
                            + code + "'");
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
