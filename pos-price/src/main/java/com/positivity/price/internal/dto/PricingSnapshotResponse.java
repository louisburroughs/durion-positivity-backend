package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Response payload for immutable pricing snapshot retrieval.
 *
 * Issue: #50
 */
@Schema(description = "Response payload for immutable pricing snapshot retrieval")
public class PricingSnapshotResponse {

    @Schema(description = "Snapshot identifier", example = "4fd2de29-650f-4d97-9b80-01b476d9ce31", requiredMode = REQUIRED)
    @NotNull
    private UUID snapshotId;

    @Schema(description = "Snapshot creation timestamp", example = "2026-03-05T21:38:21.752Z", requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Optional source context",
            example = "quote-checkout",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String sourceContext;

    @Schema(description = "Identifier of the quoted item", example = "SKU:P001", requiredMode = REQUIRED)
    @NotNull
    private String itemIdentifier;

    @Schema(description = "Quoted quantity", example = "1", requiredMode = REQUIRED)
    @NotNull
    private Integer quantity;

    @Schema(
            description = "Serialized prices payload",
            example = "{\"unitPrice\":100.00,\"total\":100.00}",
            requiredMode = REQUIRED)
    @NotNull
    private String prices;

    @Schema(description = "Serialized applied-rule details", nullable = true, requiredMode = NOT_REQUIRED)
    @Nullable
    private String appliedRules;

    @Schema(description = "Pricing policy version used", example = "2026.03.1", requiredMode = REQUIRED)
    @NotNull
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
