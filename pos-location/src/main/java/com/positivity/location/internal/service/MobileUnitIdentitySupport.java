package com.positivity.location.internal.service;

import com.positivity.location.internal.exception.InvalidFieldException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Validation and normalization for a mobile unit's duty ceiling and identity fields
 * (DECISION-LOCATION-029, issue #2267): {@code maxDutyClass} (1–8, the same GVWR axis bays use,
 * V8) plus the optional, display-only {@code unitNumber}, {@code vin}, {@code licensePlate} and
 * {@code plateRegion}. Not a Spring bean, the same reason {@link LifecycleStatusSupport} is not
 * one: {@code MobileUnitServiceImpl} already owns the entity's fields, and a plain collaborator
 * keeps its constructor as it is.
 *
 * <p>None of these fields are read by scheduling or eligibility — they exist for a technician or
 * dispatcher to recognise the vehicle, never to derive equipment, crew or hours (spec D14.2).
 */
public final class MobileUnitIdentitySupport {

    public static final String FIELD_MAX_DUTY_CLASS = "maxDutyClass";
    public static final String FIELD_UNIT_NUMBER = "unitNumber";
    public static final String FIELD_VIN = "vin";
    public static final String FIELD_LICENSE_PLATE = "licensePlate";
    public static final String FIELD_PLATE_REGION = "plateRegion";

    private static final int MIN_DUTY_CLASS = 1;
    private static final int MAX_DUTY_CLASS = 8;

    private static final int UNIT_NUMBER_MAX_LENGTH = 32;
    private static final int LICENSE_PLATE_MAX_LENGTH = 16;

    /**
     * A real VIN is exactly 17 characters drawn from the 33-character VIN alphabet, which excludes
     * I, O and Q (ISO 3779) because each is easily confused with 1, 0 or 0/D on older plates and
     * odometers. The pattern is matched only after {@link #normalizeVin} upper-cases the input, so
     * the excluded letters are checked in one case.
     */
    private static final Pattern VIN_PATTERN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");

    /**
     * ISO 3166-2: a 2-letter country code, a hyphen, and a 1-3 character alphanumeric subdivision
     * code, e.g. {@code US-NC}. The 6-character column width is exactly this pattern's maximum.
     */
    private static final Pattern PLATE_REGION_PATTERN = Pattern.compile("^[A-Z]{2}-[A-Z0-9]{1,3}$");

    private MobileUnitIdentitySupport() {}

    /** {@code null} passes through unconstrained; otherwise 400 unless it is a whole class 1–8. */
    public static @Nullable Integer requireMaxDutyClass(@Nullable Integer value) {
        if (value == null) {
            return null;
        }
        if (value < MIN_DUTY_CLASS || value > MAX_DUTY_CLASS) {
            throw InvalidFieldException.invalid(
                    FIELD_MAX_DUTY_CLASS,
                    "maxDutyClass must be a GVWR class between " + MIN_DUTY_CLASS + " and " + MAX_DUTY_CLASS);
        }
        return value;
    }

    /** {@code null}/blank clears the field; otherwise trimmed text within the column width, else 400. */
    public static @Nullable String normalizeUnitNumber(@Nullable String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null && trimmed.length() > UNIT_NUMBER_MAX_LENGTH) {
            throw InvalidFieldException.invalid(
                    FIELD_UNIT_NUMBER, "unitNumber must be at most " + UNIT_NUMBER_MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * {@code null}/blank clears the field; otherwise the value upper-cased and trimmed, refused with
     * 400 unless it is exactly 17 characters drawn from the VIN alphabet (never I, O or Q).
     */
    public static @Nullable String normalizeVin(@Nullable String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!VIN_PATTERN.matcher(normalized).matches()) {
            throw InvalidFieldException.invalid(
                    FIELD_VIN, "vin must be exactly 17 characters and must not contain I, O or Q");
        }
        return normalized;
    }

    /** {@code null}/blank clears the field; otherwise trimmed text within the column width, else 400. */
    public static @Nullable String normalizeLicensePlate(@Nullable String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null && trimmed.length() > LICENSE_PLATE_MAX_LENGTH) {
            throw InvalidFieldException.invalid(
                    FIELD_LICENSE_PLATE, "licensePlate must be at most " + LICENSE_PLATE_MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * {@code null}/blank clears the field; otherwise the value upper-cased and trimmed, refused with
     * 400 unless it matches ISO 3166-2 ({@code CC-SSS}: a 2-letter country code, a hyphen, and a
     * 1-3 character alphanumeric subdivision code — for example {@code US-NC}).
     */
    public static @Nullable String normalizePlateRegion(@Nullable String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!PLATE_REGION_PATTERN.matcher(normalized).matches()) {
            throw InvalidFieldException.invalid(
                    FIELD_PLATE_REGION, "plateRegion must be an ISO 3166-2 code, for example US-NC");
        }
        return normalized;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
