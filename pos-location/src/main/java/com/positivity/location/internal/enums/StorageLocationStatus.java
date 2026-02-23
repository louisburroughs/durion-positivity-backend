package com.positivity.location.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * Lifecycle status for storage locations.
 */
public enum StorageLocationStatus {
    ACTIVE,
    INACTIVE,
    MAINTENANCE,
    QUARANTINED;

    @JsonCreator
    public static StorageLocationStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return StorageLocationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
