package com.positivity.domainevents.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The one definition of a consistent cash-rounding triple on {@link PaymentSettledV1} (ADR-0067 A8, CAP:550 #2662,
 * Invoicing &amp; Payments ruling D3), shared by the publisher (pos-invoice records a tender only when it holds) and
 * the consumers (pos-accounting holds a fact that does not): {@code amount} is the amount applied to the invoice,
 * {@code settledAmount} the cash that changed hands after rounding to the register's {@code cashIncrement}, and
 * {@code cashRoundingAmount = settledAmount − amount}. Without an increment the three must agree exactly; with one,
 * {@code settledAmount} must be {@code amount} rounded HALF_UP to a multiple of it. Only a CASH tender rounds.
 */
public final class CashRoundingTriple {

    private CashRoundingTriple() {}

    /** Whether the fact carries any of the three rounding fields. */
    public static boolean present(@NonNull PaymentSettledV1 fact) {
        return fact.settledAmount() != null || fact.cashRoundingAmount() != null || fact.cashIncrement() != null;
    }

    /** The fact's signed rounding, zero when it carries none. */
    public static @NonNull BigDecimal roundingOrZero(@NonNull PaymentSettledV1 fact) {
        return fact.cashRoundingAmount() == null ? BigDecimal.ZERO : fact.cashRoundingAmount();
    }

    /** Why the fact's triple does not hold together, or empty when it does or the fact carries none. */
    public static @NonNull Optional<String> problem(@NonNull PaymentSettledV1 fact) {
        if (!present(fact)) {
            return Optional.empty();
        }
        String method =
                fact.methodType() == null ? "" : fact.methodType().trim().toUpperCase(Locale.ROOT);
        if (!"CASH".equals(method)) {
            return Optional.of("rounding fields on a " + fact.methodType()
                    + " tender: only a cash tender rounds to the cash increment");
        }
        if (fact.amount() == null || fact.settledAmount() == null || fact.cashRoundingAmount() == null) {
            return Optional.of("a cash tender with rounding must state amount, settledAmount and cashRoundingAmount");
        }
        return problem(fact.amount(), fact.settledAmount(), fact.cashRoundingAmount(), fact.cashIncrement());
    }

    /** Why a stated triple does not hold together, or empty when it does. */
    public static @NonNull Optional<String> problem(
            @NonNull BigDecimal amount,
            @NonNull BigDecimal settledAmount,
            @NonNull BigDecimal cashRoundingAmount,
            @Nullable BigDecimal cashIncrement) {
        if (cashIncrement == null) {
            if (settledAmount.compareTo(amount) != 0) {
                return Optional.of("settledAmount must equal amount when no cashIncrement applies to the currency");
            }
            if (cashRoundingAmount.signum() != 0) {
                return Optional.of("cashRoundingAmount must be 0 when no cashIncrement applies to the currency");
            }
            return Optional.empty();
        }
        if (cashIncrement.signum() <= 0) {
            return Optional.of("cashIncrement must be greater than zero");
        }
        if (settledAmount.subtract(amount).compareTo(cashRoundingAmount) != 0) {
            return Optional.of("cashRoundingAmount must equal settledAmount minus amount ("
                    + settledAmount.subtract(amount) + ")");
        }
        // One recompute covers the grid and the half-increment bound: a HALF_UP result is on the grid and never
        // more than half an increment from the amount, so an equal settledAmount satisfies both.
        BigDecimal expected =
                amount.divide(cashIncrement, 0, RoundingMode.HALF_UP).multiply(cashIncrement);
        if (expected.compareTo(settledAmount) != 0) {
            return Optional.of("settledAmount " + settledAmount + " is not amount " + amount
                    + " rounded HALF_UP to the nearest " + cashIncrement + " (expected " + expected + ")");
        }
        return Optional.empty();
    }
}
