package com.positivity.inventory.internal.exception;

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
    private final int currentCapacity;
    private final int maxCapacity;

    public LocationAtCapacityException(UUID locationId, int currentCapacity, int maxCapacity) {
        super(
                ERROR_CODE,
                String.format(
                        "Location %s is at full capacity (%d/%d units)", locationId, currentCapacity, maxCapacity));
        this.locationId = locationId;
        this.currentCapacity = currentCapacity;
        this.maxCapacity = maxCapacity;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public int getCurrentCapacity() {
        return currentCapacity;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }
}
