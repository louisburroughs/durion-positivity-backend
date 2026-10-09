package com.positivity.order.internal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import org.jspecify.annotations.NonNull;

/**
 * Whether a drawer amount fits its currency's minor unit (ADR-0067 PC-6; CAP:550 S32d items 5 and 6).
 *
 * <p>An amount is representable when {@code amount.stripTrailingZeros().scale()}, counted as 0 when negative, is at
 * most the ISO 4217 exponent of the currency, taken from the JDK's ISO data that ADR-0067 pins. Trailing zeros do not
 * count, and an amount is never rounded: one that does not fit is refused with 422 {@code
 * AMOUNT_PRECISION_EXCEEDS_CURRENCY}. pos-order has its own helper because pos-accounting's {@code MinorUnit} is
 * internal to that module.
 */
public final class DrawerAmounts {

    private DrawerAmounts() {}

    /**
     * The currency's ISO 4217 exponent; 0 for a code the JDK gives none (a pseudo-currency).
     *
     * @param currencyCode an ISO 4217 code
     * @return the number of minor-unit digits
     */
    public static int exponent(@NonNull String currencyCode) {
        return Math.max(0, Currency.getInstance(currencyCode).getDefaultFractionDigits());
    }

    /**
     * Whether {@code amount} fits the minor unit of {@code currencyCode}.
     *
     * @param amount       the amount
     * @param currencyCode its ISO 4217 code
     * @return true when no digit lies below the minor unit
     */
    public static boolean representable(@NonNull BigDecimal amount, @NonNull String currencyCode) {
        return Math.max(0, amount.stripTrailingZeros().scale()) <= exponent(currencyCode);
    }

    /**
     * A representable amount stated at its currency's exponent ({@code 40.000} CAD is {@code 40.00}); it never
     * rounds, so it throws for an amount {@link #representable} refuses.
     *
     * @param amount       a representable amount
     * @param currencyCode its ISO 4217 code
     * @return the same value at the currency's exponent
     */
    public static @NonNull BigDecimal atExponent(@NonNull BigDecimal amount, @NonNull String currencyCode) {
        return amount.setScale(exponent(currencyCode), RoundingMode.UNNECESSARY);
    }
}
