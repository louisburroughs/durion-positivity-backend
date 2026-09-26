package com.positivity.location.internal.service;

import com.positivity.location.internal.exception.InvalidFieldException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Converts a distance carried at the API edge as {@code {value, unit}} (KM or MI) to and from the
 * platform's canonical storage unit, kilometres (DECISION-LOCATION-028).
 *
 * <p>A bare number is never a valid distance: every distance must name its unit explicitly, so the
 * parse methods here only ever accept a {@code Map} shaped like {@code {"value": ..., "unit": ...}}
 * (which is what a JSON object deserializes to for an untyped field, and what a hand-built patch map
 * carries too) and refuse anything else — including a raw number or string — 400 {@code
 * VALIDATION_ERROR} naming the field.
 */
public final class DistanceUnits {

    public static final String KM = "KM";
    public static final String MI = "MI";

    private static final String VALUE_KEY = "value";
    private static final String UNIT_KEY = "unit";

    /** 1 mile = 1.609344 km, exact (DECISION-LOCATION-028 rule 3). */
    private static final BigDecimal MILES_TO_KM = new BigDecimal("1.609344");

    private static final int DISPLAY_SCALE = 2;

    private DistanceUnits() {}

    /** {@code KM} or {@code MI}, matched case-insensitively; {@code null} for anything else. */
    @Nullable
    public static String normalize(@Nullable String unit) {
        if (unit == null) {
            return null;
        }
        String upper = unit.trim().toUpperCase(Locale.ROOT);
        return KM.equals(upper) || MI.equals(upper) ? upper : null;
    }

    /**
     * Parses a required {@code {value, unit}} distance, converting to kilometres. 400 {@code
     * VALIDATION_ERROR} naming {@code field} when it is absent, a bare number/string, or otherwise
     * malformed.
     */
    public static BigDecimal parseRequiredKm(@Nullable Object raw, String field) {
        BigDecimal km = parseOptionalKm(raw, field);
        if (km == null) {
            throw InvalidFieldException.invalid(field, field + " is required");
        }
        return km;
    }

    /**
     * Parses an optional {@code {value, unit}} distance, converting to kilometres; {@code null} for
     * a {@code null} input. 400 {@code VALIDATION_ERROR} naming {@code field} for anything present
     * but malformed — including a bare number or string, which must always carry an explicit unit,
     * a missing or non-numeric value, a negative value, or a unit other than KM/MI.
     */
    @Nullable
    public static BigDecimal parseOptionalKm(@Nullable Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw InvalidFieldException.invalid(
                    field, field + " must be an object with value and unit, not a bare number");
        }
        BigDecimal value = toBigDecimal(map.get(VALUE_KEY));
        if (value == null) {
            throw InvalidFieldException.invalid(field + ".value", field + ".value is required and must be a number");
        }
        if (value.signum() < 0) {
            throw InvalidFieldException.invalid(field + ".value", field + ".value must not be negative");
        }
        Object rawUnit = map.get(UNIT_KEY);
        String unit = normalize(rawUnit == null ? null : String.valueOf(rawUnit));
        if (unit == null) {
            throw InvalidFieldException.invalid(field + ".unit", field + ".unit must be KM or MI");
        }
        return toKm(value, unit);
    }

    /** Converts a value already known to be in {@code unit} (KM or MI) to kilometres, rounded half-up to 2dp. */
    public static BigDecimal toKm(BigDecimal value, String unit) {
        BigDecimal km = MI.equals(unit) ? value.multiply(MILES_TO_KM) : value;
        return km.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Converts a stored kilometre value back to {@code unit} (KM or MI) for display, rounded
     * half-up to 2dp; {@code null} stays {@code null}.
     */
    @Nullable
    public static BigDecimal fromKm(@Nullable BigDecimal km, String unit) {
        if (km == null) {
            return null;
        }
        BigDecimal value = MI.equals(unit) ? km.divide(MILES_TO_KM, 10, RoundingMode.HALF_UP) : km;
        return value.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
    }

    @Nullable
    private static BigDecimal toBigDecimal(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
