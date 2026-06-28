package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.OverrideReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Request DTO for executing a putaway move from staging to storage.
 *
 * <p>
 * Represents the clerk's scan data and optional override information.
 */
@Schema(
        description =
                "Request to execute a putaway move from staging to storage, with optional business-rule overrides")
public class PutawayExecutionRequest {

    @Schema(
            description = "Identifier of the SKU being moved",
            example = "01960003-0000-7000-8000-000000000120",
            requiredMode = REQUIRED)
    @NotBlank
    private String skuId;

    @Schema(
            description = "Identifier of the staging location the goods are moved from",
            example = "01960003-0000-7000-8000-000000000121",
            requiredMode = REQUIRED)
    @NotNull
    private UUID sourceLocationId;

    @Schema(
            description = "Identifier of the storage location the goods are moved to",
            example = "01960003-0000-7000-8000-000000000122",
            requiredMode = REQUIRED)
    @NotNull
    private UUID destinationLocationId;

    @Schema(description = "Quantity of units to move", example = "12", requiredMode = REQUIRED)
    @Positive
    private int quantity;

    // Override fields (optional)
    @Schema(
            description = "When true, bypasses the location compatibility check for this move",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private boolean overrideLocationCompatibility;

    @Schema(
            description = "When true, bypasses the destination capacity check for this move",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private boolean overrideCapacity;

    @Schema(
            description = "Audit reason code justifying any applied override",
            example = "CAPACITY_OVERRIDE",
            requiredMode = NOT_REQUIRED)
    private OverrideReasonCode overrideReasonCode;

    @Schema(
            description = "Free-text justification supporting the override",
            example = "Receiving dock at capacity during peak intake",
            requiredMode = NOT_REQUIRED)
    private String overrideJustification;

    @Schema(
            description = "Identifier of the supervisor who approved the override",
            example = "01960003-0000-7000-8000-000000000123",
            requiredMode = NOT_REQUIRED)
    private String approvedBy;

    public PutawayExecutionRequest() {}

    public PutawayExecutionRequest(String skuId, UUID sourceLocationId, UUID destinationLocationId, int quantity) {
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
