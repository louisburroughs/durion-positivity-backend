package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating immutable pricing snapshots.
 *
 * Issue: #50
 */
@Schema(description = "Request payload to create an immutable pricing snapshot")
public class PricingSnapshotCreateRequest {

    @Schema(description = "Optional source context for diagnostics", example = "quote-checkout", nullable = true)
    @Size(max = 255, message = "sourceContext must not exceed 255 characters")
    private String sourceContext;

    @Schema(
            description = "Identifier of the quoted item or aggregate object",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SKU:P001")
    @NotBlank(message = "itemIdentifier is required")
    @Size(max = 255, message = "itemIdentifier must not exceed 255 characters")
    private String itemIdentifier;

    @Schema(
            description = "Quantity represented by this snapshot",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    private Integer quantity;

    @Schema(
            description = "Serialized prices payload",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "{\"unitPrice\":100.00,\"total\":100.00}")
    @NotBlank(message = "prices is required")
    private String prices;

    @Schema(description = "Serialized rule application details", nullable = true)
    private String appliedRules;

    @Schema(
            description = "Pricing policy version used for this snapshot",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026.03.1")
    @NotBlank(message = "policyVersion is required")
    @Size(max = 255, message = "policyVersion must not exceed 255 characters")
    private String policyVersion;

    public String getSourceContext() {
        return sourceContext;
    }

    public void setSourceContext(String sourceContext) {
        this.sourceContext = sourceContext;
    }

    public String getItemIdentifier() {
        return itemIdentifier;
    }

    public void setItemIdentifier(String itemIdentifier) {
        this.itemIdentifier = itemIdentifier;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getPrices() {
        return prices;
    }

    public void setPrices(String prices) {
        this.prices = prices;
    }

    public String getAppliedRules() {
        return appliedRules;
    }

    public void setAppliedRules(String appliedRules) {
        this.appliedRules = appliedRules;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }
}
