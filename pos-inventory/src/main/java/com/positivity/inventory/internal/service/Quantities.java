package com.positivity.inventory.internal.service;

import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Arithmetic helpers for the decimal inventory quantities the ledger and its read model carry
 * (ADR-0055, #1414).
 *
 * <p>Exists for two reasons the widening from {@code int}/{@code long} made necessary. First,
 * every quantity that used to be a primitive is now a reference and can be null, and a null
 * quantity on a ledger entry has always meant zero — {@link #nz} keeps that reading in one place
 * instead of scattering ternaries. Second, {@code BigDecimal.equals} compares scale as well as
 * value, so {@code 1} and {@code 1.0} are unequal to it while being the same quantity; every
 * comparison here goes through {@code compareTo} so no caller has to remember that.
 */
final class Quantities {

    private Quantities() {}

    /** The value, or {@code ZERO} when absent. An absent quantity has always meant none. */
    static @NonNull BigDecimal nz(@Nullable BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** {@code a > b} by value, ignoring scale. */
    static boolean gt(@Nullable BigDecimal a, @Nullable BigDecimal b) {
        return nz(a).compareTo(nz(b)) > 0;
    }

    /** {@code a >= b} by value, ignoring scale. */
    static boolean gte(@Nullable BigDecimal a, @Nullable BigDecimal b) {
        return nz(a).compareTo(nz(b)) >= 0;
    }

    /** {@code a < b} by value, ignoring scale. */
    static boolean lt(@Nullable BigDecimal a, @Nullable BigDecimal b) {
        return nz(a).compareTo(nz(b)) < 0;
    }

    /** Whether the value is strictly positive. */
    static boolean isPositive(@Nullable BigDecimal value) {
        return nz(value).signum() > 0;
    }

    /** Whether the value is strictly negative. */
    static boolean isNegative(@Nullable BigDecimal value) {
        return nz(value).signum() < 0;
    }

    /** Whether the value is zero, at any scale. */
    static boolean isZero(@Nullable BigDecimal value) {
        return nz(value).signum() == 0;
    }

    /** The lesser of two quantities by value. */
    static @NonNull BigDecimal min(@Nullable BigDecimal a, @Nullable BigDecimal b) {
        return nz(a).compareTo(nz(b)) <= 0 ? nz(a) : nz(b);
    }

    /** The value floored at zero — negatives collapse to {@code ZERO}. */
    static @NonNull BigDecimal atLeastZero(@Nullable BigDecimal value) {
        return isNegative(value) ? BigDecimal.ZERO : nz(value);
    }
}
