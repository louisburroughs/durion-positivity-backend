package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.exception.CatalogValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Shared shape rules for labor-time values (#1569, sourcing plan §1): book time is decimal hours
 * in tenths, and operation codes are uppercase alphanumeric segments joined by single dashes.
 * Used by both the catalog-item taxonomy fields and the labor-standard authoring surface so the
 * two write paths cannot drift.
 */
final class LaborTimeValidation {

    static final Pattern OPERATION_CODE_PATTERN = Pattern.compile("[A-Z0-9]+(?:-[A-Z0-9]+)*");

    private static final BigDecimal MAX_LABOR_HOURS = new BigDecimal("9999.9");

    private LaborTimeValidation() {
        // Utility class - prevent instantiation
    }

    /**
     * A finer scale than tenths means the caller is sending minutes-derived precision this model
     * deliberately does not store. Returns the value normalized to scale 1; null passes through
     * for optional fields — required-ness is the caller's rule.
     */
    static BigDecimal validatedTenthsHours(BigDecimal hours, String fieldName) {
        if (hours == null) {
            return null;
        }
        if (hours.signum() <= 0
                || hours.compareTo(MAX_LABOR_HOURS) > 0
                || hours.stripTrailingZeros().scale() > 1) {
            throw new CatalogValidationException(
                    fieldName + " must be between 0.1 and 9999.9 in tenths of an hour: " + hours);
        }
        return hours.setScale(1, RoundingMode.UNNECESSARY);
    }

    /** Validates shape only; uniqueness and existence are the caller's concern. */
    static String validatedOperationCodeShape(String operationCode, String fieldName) {
        String normalized = operationCode == null ? null : operationCode.trim();
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 64
                || !OPERATION_CODE_PATTERN.matcher(normalized).matches()) {
            throw new CatalogValidationException(fieldName
                    + " must be uppercase alphanumeric segments joined by single dashes"
                    + " (e.g. BRAKE-PAD-FRONT), at most 64 characters: " + operationCode);
        }
        return normalized;
    }
}
