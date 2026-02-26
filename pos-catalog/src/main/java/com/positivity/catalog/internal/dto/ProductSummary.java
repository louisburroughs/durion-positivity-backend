package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight product summary returned by the catalog search endpoint.
 * Contains display-ready fields for listing views; does NOT include pricing
 * or availability (those are aggregated by the detail endpoint).
 *
 * Issue: CAP-247 Story #17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Lightweight product summary for search results")
public class ProductSummary {

    @Schema(description = "Unique product identifier")
    private UUID productId;

    @Schema(description = "Product name", example = "Heavy Duty Wrench")
    private String name;

    @Schema(description = "Stock Keeping Unit", example = "SKU-12345")
    private String sku;

    @Schema(description = "Category name", example = "Hand Tools")
    private String category;

    @Schema(description = "URL of the primary product thumbnail image")
    private String thumbnailUrl;

    @Schema(description = "Manufacturer brand name", example = "AcmePro")
    private String manufacturerBrand;
}
