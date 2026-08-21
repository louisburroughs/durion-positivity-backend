package com.positivity.inventory.internal.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a destination location is at full capacity.
 *
 * <p>As per clarification #229 Q1:
 * - Default behavior: Block the putaway to that location
 * - Override allowed only if OVERRIDE_LOCATION_CAPACITY permission exists,
 *   overfill is within tolerance (e.g., ≤ 5-10%), and justification is captured
 */
public class LocationAtCapacityException extends PutawayValidationException {
    public static final String ERROR_CODE = "LOCATION_AT_CAPACITY";

    private final UUID locationId;
    private final transient BigDecimal currentCapacity;
    private final transient BigDecimal maxCapacity;

    public LocationAtCapacityException(UUID locationId, BigDecimal currentCapacity, BigDecimal maxCapacity) {
        super(
                ERROR_CODE,
                String.format(
                        "Location %s is at full capacity (%s/%s units)",
                        locationId, plain(currentCapacity), plain(maxCapacity)));
        this.locationId = locationId;
        this.currentCapacity = currentCapacity;
        this.maxCapacity = maxCapacity;
    }

    // Building the error message must never itself throw and mask the capacity breach being reported.
    private static String plain(BigDecimal value) {
        return value == null ? "unknown" : value.toPlainString();
    }

    public UUID getLocationId() {
        return locationId;
    }

    public BigDecimal getCurrentCapacity() {
        return currentCapacity;
    }

    public BigDecimal getMaxCapacity() {
        return maxCapacity;
    }
}
