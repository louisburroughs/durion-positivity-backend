package com.positivity.price.internal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Contextual quote pricing request.
 *
 * Issue: #51
 */
public class PriceQuoteRequest {

    @NotNull
    private UUID productId;

    @NotNull
    @Min(1)
    private Integer quantity;

    @NotNull
    private UUID locationId;

    @NotNull
    private UUID customerTierId;

    private Instant effectiveTimestamp;

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public UUID getCustomerTierId() {
        return customerTierId;
    }

    public void setCustomerTierId(UUID customerTierId) {
        this.customerTierId = customerTierId;
    }

    public Instant getEffectiveTimestamp() {
        return effectiveTimestamp;
    }

    public void setEffectiveTimestamp(Instant effectiveTimestamp) {
        this.effectiveTimestamp = effectiveTimestamp;
    }
}
