package com.positivity.price.internal.dto;

import java.util.List;
import java.util.UUID;

/**
 * Contextual quote pricing response payload.
 *
 * Issue: #51
 */
public class PriceQuoteResponse {

    private UUID productId;
    private Integer quantity;
    private MoneyAmount msrp;
    private MoneyAmount unitPrice;
    private MoneyAmount extendedPrice;
    private String priceSource;
    private List<PricingBreakdownEntry> pricingBreakdown;
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
