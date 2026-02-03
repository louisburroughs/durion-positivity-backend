package com.positivity.vehicle.internal.util;

import org.jspecify.annotations.NonNull;

import java.util.regex.Pattern;

/**
 * Utility class for VIN validation and normalization per CAP:091 Story #105.
 */
public final class VinUtils {

    private static final Pattern VIN_PATTERN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");
    private static final String INVALID_CHARS = "IOQ";

    private VinUtils() {
        // Utility class
    }

    /**
     * Normalizes a VIN by removing separators, trimming, and converting to
     * uppercase.
     */
    public static String normalize(@NonNull String vin) {
        if (vin == null || vin.isBlank()) {
            throw new IllegalArgumentException("VIN cannot be null or blank");
        }

        return vin.trim()
                .toUpperCase()
                .replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Validates VIN format: 17 characters, no I/O/Q.
     */
    public static boolean isValid(@NonNull String vinNormalized) {
        if (vinNormalized == null || vinNormalized.length() != 17) {
            return false;
        }

        if (!VIN_PATTERN.matcher(vinNormalized).matches()) {
            return false;
        }

        // Check for invalid characters
        for (char c : INVALID_CHARS.toCharArray()) {
            if (vinNormalized.indexOf(c) >= 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Validates and normalizes a VIN, throwing exception if invalid.
     */
    public static String validateAndNormalize(@NonNull String vin) {
        String normalized = normalize(vin);
        if (!isValid(normalized)) {
            throw new IllegalArgumentException(
                    "Invalid VIN format. VIN must be 17 characters and cannot contain I, O, or Q. Provided: " + vin);
        }
        return normalized;
    }
}
