package com.positivity.accounting.internal.service;

import com.positivity.domainevents.payment.PaymentSettledV1;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0067 A8 (CAP:550 #2662, item 12, AC 20): pos-accounting validates the cash-rounding triple on a settled fact
 * before it records or posts anything. {@code amount} is the amount applied to the invoice; {@code settledAmount} the
 * cash that changed hands; {@code cashRoundingAmount = settledAmount − amount}; {@code cashIncrement} the coin the
 * register rounded to. A fact without the three fields (published before A8, or a card tender) carries no rounding.
 */
final class CashRoundingFactCheck {

    private CashRoundingFactCheck() {}

    /** The rounding to post (signed, zero when none), or empty when the fact is not a cash tender with rounding. */
    static @NonNull BigDecimal roundingOf(@NonNull PaymentSettledV1 fact) {
        return fact.cashRoundingAmount() == null ? BigDecimal.ZERO : fact.cashRoundingAmount();
    }

    /** Why the triple does not hold together, or empty when it does (or there is none). */
    static @NonNull Optional<String> problem(@NonNull PaymentSettledV1 fact) {
        BigDecimal amount = fact.amount();
        BigDecimal settled = fact.settledAmount();
        BigDecimal rounding = fact.cashRoundingAmount();
        BigDecimal increment = fact.cashIncrement();
        boolean cash = fact.methodType() != null
                && "CASH".equals(fact.methodType().trim().toUpperCase(Locale.ROOT));
        if (settled == null && rounding == null && increment == null) {
            return Optional.empty();
        }
        if (!cash) {
            return Optional.of("rounding fields on a " + fact.methodType()
                    + " tender: only a cash tender rounds to the cash increment");
        }
        if (amount == null || settled == null || rounding == null) {
            return Optional.of("a cash tender with rounding must state amount, settledAmount and cashRoundingAmount");
        }
        if (settled.subtract(amount).compareTo(rounding) != 0) {
            return Optional.of(
                    "cashRoundingAmount " + rounding + " is not settledAmount " + settled + " minus amount " + amount);
        }
        if (increment == null) {
            if (rounding.signum() != 0) {
                return Optional.of("cashRoundingAmount " + rounding + " without a cashIncrement");
            }
            return Optional.empty();
        }
        if (increment.signum() <= 0) {
            return Optional.of("cashIncrement " + increment + " is not positive");
        }
        BigDecimal expected = amount.divide(increment, 0, RoundingMode.HALF_UP).multiply(increment);
        if (expected.compareTo(settled) != 0) {
            return Optional.of("settledAmount " + settled + " is not amount " + amount + " rounded HALF_UP to the"
                    + " nearest " + increment + " (expected " + expected + ")");
        }
        return Optional.empty();
    }
}
