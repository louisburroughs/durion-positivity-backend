package com.positivity.price.internal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for creating immutable pricing snapshots.
 *
 * Issue: #50
 */
public class PricingSnapshotCreateRequest {

    private String sourceContext;

    @NotBlank
    private String itemIdentifier;

    @NotNull
    @Min(1)
    private Integer quantity;

    @NotBlank
    private String prices;

    private String appliedRules;

    @NotBlank
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
