package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when a destination location is not valid for a given SKU.
 *
 * <p>Since issue #1514 the reasons are: the destination is not the target of any enabled putaway
 * rule; its storage class does not accept the item's catalog class; it is a {@code STAGING} or
 * {@code QUARANTINE} location, which are putaway sources rather than destinations; or hazard
 * containment is required — by the matched compatibility row, or by the item's own class where every
 * class the matrix accepts for it requires containment — and the destination does not declare it.
 *
 * <p>As per clarification #229 Q1, default behavior is to block the putaway transaction.
 * Override requires OVERRIDE_LOCATION_COMPATIBILITY permission.
 */
public class LocationNotValidForSkuException extends PutawayValidationException {
    public static final String ERROR_CODE = "LOCATION_NOT_VALID_FOR_SKU";

    private final UUID locationId;
    private final String skuId;
    private final String reason;

    public LocationNotValidForSkuException(UUID locationId, String skuId, String reason) {
        super(ERROR_CODE, String.format("Location %s is not valid for SKU %s. Reason: %s", locationId, skuId, reason));
        this.locationId = locationId;
        this.skuId = skuId;
        this.reason = reason;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public String getSkuId() {
        return skuId;
    }

    public String getReason() {
        return reason;
    }
}
