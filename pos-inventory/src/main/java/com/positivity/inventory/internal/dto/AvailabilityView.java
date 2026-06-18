package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Read-only view of on-hand and available-to-promise quantities for a product
 * at a specific location scope.
 *
 * <p>
 * ATP = onHandQuantity - allocatedQuantity (ADR-0001)
 *
 * Issue: CAP-215 Story #36
 */
@Schema(
        description =
                "Read-only view of on-hand and available-to-promise quantities for a product at a specific location scope")
@Value
@Builder
public class AvailabilityView {

    /** Stock-keeping unit identifier. */
    @Schema(
            description = "Stock-keeping unit identifier of the product",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    String productSku;

    /** Location identifier for the scope of this availability view. */
    @Schema(
            description = "Location identifier for the scope of this availability view",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    UUID locationId;

    /** Optional sub-location identifier (null when scoped to full location). */
    @Schema(
            description = "Optional storage sub-location identifier; null when scoped to the full location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    UUID storageLocationId;

    /** Net on-hand quantity (sum of affectsOnHand entries). */
    @Schema(
            description = "Net on-hand quantity (sum of affectsOnHand ledger entries)",
            example = "120",
            requiredMode = REQUIRED)
    int onHandQuantity;

    /** Net allocated quantity (ALLOCATION_CREATED minus ALLOCATION_RELEASED). */
    @Schema(
            description = "Net allocated quantity (ALLOCATION_CREATED minus ALLOCATION_RELEASED)",
            example = "30",
            requiredMode = REQUIRED)
    int allocatedQuantity;

    /** ATP = onHandQuantity - allocatedQuantity. */
    @Schema(
            description = "Available-to-promise quantity, equal to onHandQuantity minus allocatedQuantity",
            example = "90",
            requiredMode = REQUIRED)
    int availableToPromiseQuantity;

    /** Unit of measure code (e.g. EACH, KG). */
    @Schema(
            description = "Unit of measure code for the quantities (e.g. EACH, KG)",
            example = "EACH",
            requiredMode = REQUIRED)
    @NotNull
    String unitOfMeasure;
}
