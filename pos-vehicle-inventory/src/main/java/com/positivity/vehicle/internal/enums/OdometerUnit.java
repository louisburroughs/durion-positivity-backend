package com.positivity.vehicle.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Supported odometer distance units.
 */
public enum OdometerUnit {
    MILES,
    KILOMETERS;

    @JsonCreator
    public static OdometerUnit fromJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MI", "MILE", "MILES" -> MILES;
            case "KM", "KILOMETER", "KILOMETERS", "KILOMETRE", "KILOMETRES" -> KILOMETERS;
            default -> valueOf(normalized);
        };
    }

    @JsonValue
    public String toJson() {
        return name();
    }
}
