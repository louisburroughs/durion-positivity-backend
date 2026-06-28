package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Contextual quote pricing response payload.
 *
 * Issue: #51
 */
@Schema(description = "Calculated contextual price quote response")
public class PriceQuoteResponse {

    @Schema(
            description = "Product identifier",
            example = "7f3c35db-b908-42fa-83f1-2ef46a3c2149",
            requiredMode = REQUIRED)
    @NotNull
    private UUID productId;

    @Schema(description = "Quoted quantity", example = "2", requiredMode = REQUIRED)
    @NotNull
    private Integer quantity;

    @Schema(description = "Manufacturer suggested retail price", requiredMode = REQUIRED)
    @NotNull
    private MoneyAmount msrp;

    @Schema(description = "Final unit price after applying applicable rules", requiredMode = REQUIRED)
    @NotNull
    private MoneyAmount unitPrice;

    @Schema(description = "Extended price (unit price multiplied by quantity)", requiredMode = REQUIRED)
    @NotNull
    private MoneyAmount extendedPrice;

    @Schema(
            description = "Source from which the final price was resolved",
            example = "LOCATION_OVERRIDE",
            requiredMode = REQUIRED)
    @NotNull
    private String priceSource;

    @Schema(description = "Per-rule pricing breakdown entries", example = "[]", requiredMode = REQUIRED)
    @NotNull
    private List<PricingBreakdownEntry> pricingBreakdown;

    @Schema(
            description = "Non-fatal warnings generated during quote calculation",
            example = "[]",
            requiredMode = REQUIRED)
    @NotNull
    private List<String> warnings;

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

    public MoneyAmount getMsrp() {
        return msrp;
    }

    public void setMsrp(MoneyAmount msrp) {
        this.msrp = msrp;
    }

    public MoneyAmount getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(MoneyAmount unitPrice) {
        this.unitPrice = unitPrice;
    }

    public MoneyAmount getExtendedPrice() {
        return extendedPrice;
    }

    public void setExtendedPrice(MoneyAmount extendedPrice) {
        this.extendedPrice = extendedPrice;
    }

    public String getPriceSource() {
        return priceSource;
    }

    public void setPriceSource(String priceSource) {
        this.priceSource = priceSource;
    }

    public List<PricingBreakdownEntry> getPricingBreakdown() {
        return pricingBreakdown;
    }

    public void setPricingBreakdown(List<PricingBreakdownEntry> pricingBreakdown) {
        this.pricingBreakdown = pricingBreakdown;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
}
