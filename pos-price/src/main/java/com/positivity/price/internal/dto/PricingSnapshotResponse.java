package com.positivity.price.internal.dto;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Response payload for immutable pricing snapshot retrieval.
 *
 * Issue: #50
 */
public class PricingSnapshotResponse {

    private UUID snapshotId;
    private Instant createdAt;
    @Nullable
    private String sourceContext;
    private String itemIdentifier;
    private Integer quantity;
    private String prices;
    @Nullable
    private String appliedRules;
    private String policyVersion;

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(UUID snapshotId) {
        this.snapshotId = snapshotId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Nullable
    public String getSourceContext() {
        return sourceContext;
    }

    public void setSourceContext(@Nullable String sourceContext) {
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

    @Nullable
    public String getAppliedRules() {
        return appliedRules;
    }

    public void setAppliedRules(@Nullable String appliedRules) {
        this.appliedRules = appliedRules;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }
}
