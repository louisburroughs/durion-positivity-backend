package com.positivity.inventory.internal.dto;

import com.positivity.inventory.internal.enums.OverrideReasonCode;

import java.util.UUID;

/**
 * Request DTO for executing a putaway move from staging to storage.
 * 
 * <p>
 * Represents the clerk's scan data and optional override information.
 */
public class PutawayExecutionRequest {
    private String skuId;
    private UUID sourceLocationId;
    private UUID destinationLocationId;
    private int quantity;

    // Override fields (optional)
    private boolean overrideLocationCompatibility;
    private boolean overrideCapacity;
    private OverrideReasonCode overrideReasonCode;
    private String overrideJustification;
    private String approvedBy;

    public PutawayExecutionRequest() {
    }

    public PutawayExecutionRequest(String skuId, UUID sourceLocationId,
            UUID destinationLocationId, int quantity) {
        this.skuId = skuId;
        this.sourceLocationId = sourceLocationId;
        this.destinationLocationId = destinationLocationId;
        this.quantity = quantity;
    }

    // Getters and setters
    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public void setSourceLocationId(UUID sourceLocationId) {
        this.sourceLocationId = sourceLocationId;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public boolean isOverrideLocationCompatibility() {
        return overrideLocationCompatibility;
    }

    public void setOverrideLocationCompatibility(boolean overrideLocationCompatibility) {
        this.overrideLocationCompatibility = overrideLocationCompatibility;
    }

    public boolean isOverrideCapacity() {
        return overrideCapacity;
    }

    public void setOverrideCapacity(boolean overrideCapacity) {
        this.overrideCapacity = overrideCapacity;
    }

    public OverrideReasonCode getOverrideReasonCode() {
        return overrideReasonCode;
    }

    public void setOverrideReasonCode(OverrideReasonCode overrideReasonCode) {
        this.overrideReasonCode = overrideReasonCode;
    }

    public String getOverrideJustification() {
        return overrideJustification;
    }

    public void setOverrideJustification(String overrideJustification) {
        this.overrideJustification = overrideJustification;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }
}
