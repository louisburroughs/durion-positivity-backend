package com.durion.inventory.exception;

/**
 * Thrown when a destination location is not valid for a given SKU.
 * Examples: temperature class mismatch, hazardous/non-hazardous rules, zone restrictions.
 * 
 * <p>As per clarification #229 Q1, default behavior is to block the putaway transaction.
 * Override requires OVERRIDE_LOCATION_COMPATIBILITY permission.
 */
public class LocationNotValidForSkuException extends PutawayValidationException {
    public static final String ERROR_CODE = "LOCATION_NOT_VALID_FOR_SKU";
    
    private final String locationId;
    private final String skuId;
    private final String reason;
    
    public LocationNotValidForSkuException(String locationId, String skuId, String reason) {
        super(ERROR_CODE, String.format(
            "Location %s is not valid for SKU %s. Reason: %s", 
            locationId, skuId, reason));
        this.locationId = locationId;
        this.skuId = skuId;
        this.reason = reason;
    }
    
    public String getLocationId() {
        return locationId;
    }
    
    public String getSkuId() {
        return skuId;
    }
    
    public String getReason() {
        return reason;
    }
}
