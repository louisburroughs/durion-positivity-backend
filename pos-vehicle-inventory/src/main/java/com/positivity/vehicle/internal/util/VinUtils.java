package com.positivity.vehicle.internal.util;

import com.positivity.vehicle.internal.exception.VehicleValidationException;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

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
        // Defensive/(d): both current call sites (createVehicle via validateAndNormalize, and
        // getVehicleByVin) only reach this method after the caller's own bean validation
        // (@NotBlank on CreateVehicleRequest.vin, @NotBlank/@Size on the getVehicleByVin path
        // variable) has already rejected a null/blank VIN, so a client request cannot trigger
        // this branch today. Left as a bare IllegalArgumentException (not retyped, not mapped by
        // the advice) rather than silently dropped, so a future caller that skips validation
        // fails loudly instead of NPEing further down (issue #1694).
        if (vin == null || vin.isBlank()) {
            throw new IllegalArgumentException("VIN cannot be null or blank");
        }

        return vin.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
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
            throw new VehicleValidationException(
                    "Invalid VIN format. VIN must be 17 characters and cannot contain I, O, or Q. Provided: " + vin);
        }
        return normalized;
    }
}
