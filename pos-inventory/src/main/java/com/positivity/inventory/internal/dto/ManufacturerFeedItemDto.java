package com.positivity.inventory.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Canonical manufacturer feed item payload for feed processing.
 *
 * Issue: CAP-170 (#46)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Canonical manufacturer feed payload used for normalized availability ingestion")
public class ManufacturerFeedItemDto {

    @Schema(description = "Internal product identifier. May be null for unmapped manufacturer parts.", example = "550e8400-e29b-41d4-a716-446655440000", nullable = true)
    private UUID productId;

    @Schema(description = "Manufacturer identifier from upstream feed", example = "660e8400-e29b-41d4-a716-446655440000", requiredMode = RequiredMode.REQUIRED)
    private UUID manufacturerId;

    @Schema(description = "Manufacturer part number", example = "MPN-12345", requiredMode = RequiredMode.REQUIRED)
    private String manufacturerPartNumber;

    @Schema(description = "Quantity currently available to fulfill", example = "120", nullable = true)
    private Integer availableQty;

    @Schema(description = "Unit of measure for quantity values", example = "EACH", nullable = true)
    private String uom;

    @Schema(description = "Unit price amount in feed currency", example = "14.99", nullable = true)
    private BigDecimal unitPriceAmount;

    @Schema(description = "ISO 4217 currency code for unit price", example = "USD", nullable = true)
    private String unitPriceCurrency;

    @Schema(description = "Minimum lead time in days", example = "2", nullable = true)
    private Integer leadTimeDaysMin;

    @Schema(description = "Maximum lead time in days", example = "5", nullable = true)
    private Integer leadTimeDaysMax;

    @Schema(description = "Minimum order quantity", example = "1", nullable = true)
    private Integer minOrderQty;

    @Schema(description = "Pack size multiple", example = "1", nullable = true)
    private Integer packSize;

    @Schema(description = "Source location or warehouse code from manufacturer feed", example = "US-EAST-DC1", nullable = true)
    private String sourceLocationCode;

    @Schema(description = "Manufacturer snapshot timestamp", example = "2026-02-24T18:00:00Z", nullable = true)
    private Instant asOf;
}
