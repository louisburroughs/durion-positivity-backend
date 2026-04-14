package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Contextual quote pricing request.
 *
 * Issue: #51
 */
@Schema(description = "Request payload for calculating contextual price quotes")
public class PriceQuoteRequest {

    @Schema(
            description = "Product identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "7f3c35db-b908-42fa-83f1-2ef46a3c2149")
    @NotNull(message = "productId is required")
    private UUID productId;

    @Schema(description = "Requested quantity", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity;

    @Schema(
            description = "Location identifier where quote applies",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "6b2896a2-d456-4e22-ab5b-8a67f32c4f59")
    @NotNull(message = "locationId is required")
    private UUID locationId;

    @Schema(
            description = "Customer tier identifier used for tier pricing rules",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ea2f17ef-f06e-40ec-a9d6-c2d1b3e56d8a")
    @NotNull(message = "customerTierId is required")
    private UUID customerTierId;

    @Schema(
            description = "Optional timestamp for effective pricing lookup; defaults to current time if omitted",
            example = "2026-03-05T21:15:00Z",
            nullable = true)
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
