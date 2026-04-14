package com.positivity.inventory.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for inventory availability query.
 *
 * <p>
 * Represents the current inventory availability for a product at a specific
 * location,
 * including on-hand quantity, allocations, and available-to-promise (ATP).
 *
 * <p>
 * ATP is calculated as: ATP = On-Hand - Allocations
 * (Expected Receipts are not included in v1 - see ADR-0001)
 *
 * <p>
 * All quantities are in the product's base unit of measure.
 *
 * @see <a href=
 *      "/docs/adr/0001-inventory-ledger-atp-computation.md">ADR-0001</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Inventory availability response including on-hand, allocations, and ATP")
public class InventoryAvailabilityResponse {

    @Schema(
            description = "Product identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = RequiredMode.REQUIRED)
    private UUID productId;

    @Schema(
            description = "Location identifier",
            example = "660e8400-e29b-41d4-a716-446655440000",
            requiredMode = RequiredMode.REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Physical on-hand quantity (net sum of ledger events)",
            example = "100.00",
            requiredMode = RequiredMode.REQUIRED)
    private BigDecimal onHandQty;

    @Schema(
            description = "Total quantity allocated (hard commitments)",
            example = "25.00",
            requiredMode = RequiredMode.REQUIRED)
    private BigDecimal allocatedQty;

    @Schema(
            description = "Available-to-promise quantity (On-Hand - Allocations)",
            example = "75.00",
            requiredMode = RequiredMode.REQUIRED)
    private BigDecimal atpQty;

    @Schema(
            description = "Base unit of measure for all quantities",
            example = "EACH",
            requiredMode = RequiredMode.REQUIRED)
    private String uom;

    @Schema(
            description = "Timestamp when this calculation was performed",
            example = "2026-01-12T22:00:00Z",
            requiredMode = RequiredMode.REQUIRED)
    private Instant asOfTimestamp;

    @Schema(
            description = "Expected receipts quantity (optional, not included in ATP for v1)",
            example = "50.00",
            nullable = true)
    private BigDecimal expectedReceiptsQty;
}
