package com.positivity.accounting.internal.bankrec.intake;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0067 PC-6 at the edge: an amount that changes hands carries at most as many significant decimals as its
 * currency's ISO 4217 exponent (2 for USD, 0 for JPY, 3 for KWD). A finer amount is refused with 422 {@link
 * BankRecErrorCode#AMOUNT_PRECISION_EXCEEDS_CURRENCY}, never rounded; trailing zeros do not count, so {@code
 * 10.0000} is {@code 10.00}.
 *
 * <p>It lives beside the intake port so that the core, the intake and the file adapter (which may reach the
 * core only through {@code ..bankrec.intake..}) share one rule and one refusal.
 */
public final class MinorUnit {

    private MinorUnit() {}

    /** Decimal places of {@code currencyCode}'s minor unit. */
    public static int fractionDigits(@NonNull String currencyCode) {
        return Math.max(0, Currency.getInstance(currencyCode).getDefaultFractionDigits());
    }

    /** Whether {@code amount} fits {@code currencyCode}'s minor unit. */
    public static boolean fits(@NonNull BigDecimal amount, @NonNull String currencyCode) {
        return amount.stripTrailingZeros().scale() <= fractionDigits(currencyCode);
    }

    /** The field-error detail of an amount finer than {@code currencyCode}'s minor unit. */
    public static @NonNull String detail(@NonNull String currencyCode) {
        return "at most " + fractionDigits(currencyCode) + " decimal places for " + currencyCode;
    }

    /** The 422 refusal naming every offending field. */
    public static @NonNull BankRecException exceeded(@NonNull Map<String, String> fieldErrors) {
        return new BankRecException(
                BankRecErrorCode.AMOUNT_PRECISION_EXCEEDS_CURRENCY,
                fieldErrors.size() + " amount(s) have more decimal places than the currency allows",
                fieldErrors);
    }
}
