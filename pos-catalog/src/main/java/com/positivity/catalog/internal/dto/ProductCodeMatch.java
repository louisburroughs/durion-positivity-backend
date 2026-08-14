package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * A single product resolved by an exact product-code match (ADR-0053 §5).
 *
 * <p>
 * The per-type uniqueness constraint on {@code (productCodeType, productCode)} guarantees that a
 * match is either absent or unique, so consumers never have to arbitrate between candidates.
 *
 * @param productId              catalog identifier of the matched product
 * @param sku                    the product's SKU; never used for code matching
 * @param name                   product name, for operator-facing display of a match
 * @param codeType               the code scheme that produced the match
 * @param code                   the exact code value that was matched
 * @param manufacturerId         manufacturer identifier, null when the product has none
 * @param manufacturerPartNumber manufacturer part number, null when the product has none
 */
@Schema(description = "Product resolved by an exact, per-type-unique product-code match.")
public record ProductCodeMatch(
        @Schema(
                description = "Catalog identifier of the matched product.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        UUID productId,

        @Schema(description = "SKU of the matched product.", example = "SKU12345")
        String sku,

        @Schema(description = "Name of the matched product.", example = "Heavy Duty Wrench")
        String name,

        @Schema(description = "Code scheme that produced the match.", implementation = ProductCodeKind.class)
        ProductCodeKind codeType,

        @Schema(description = "Exact code value that was matched.", example = "0123456789012")
        String code,

        @Schema(
                description = "Manufacturer identifier of the matched product, null when unknown.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c")
        UUID manufacturerId,

        @Schema(
                description = "Manufacturer part number of the matched product, null when unknown.",
                example = "HDW-001")
        String manufacturerPartNumber) {}
