package com.durion.inventory.exception;

/**
 * Thrown when source location is scanned but system shows zero quantity.
 * This indicates a data consistency error.
 * 
 * <p>As per clarification #229 Q2:
 * - Default behavior: Always block the putaway transaction
 * - System must NOT silently create inventory
 * - Requires reconciliation via cycle count or inventory adjustment
 */
public class NoOnHandAtSourceLocationException extends PutawayValidationException {
    public static final String ERROR_CODE = "NO_ON_HAND_AT_SOURCE_LOCATION";
    
    private final String locationId;
    private final String skuId;
    
    public NoOnHandAtSourceLocationException(String locationId, String skuId) {
        super(ERROR_CODE, String.format(
            "No on-hand inventory found for SKU %s at source location %s. " +
            "Data consistency error - reconciliation required.", 
            skuId, locationId));
        this.locationId = locationId;
        this.skuId = skuId;
    }
    
    public String getLocationId() {
        return locationId;
    }
    
    public String getSkuId() {
        return skuId;
    }
}
