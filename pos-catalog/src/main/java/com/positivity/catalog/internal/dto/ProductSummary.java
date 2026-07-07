package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.ProductLifecycleState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight product summary returned by the catalog search endpoint.
 * Contains display-ready fields for listing views.
 *
 * <p>
 * When the search endpoint is called with {@code detailed=true}, the enriched
 * lifecycle and active-MSRP fields below are resolved server-side and populated
 * inline, avoiding a per-row {@code getProductById} + {@code getActiveMsrp}
 * fan-out on the client. In the default (lean) mode these enriched fields are
 * {@code null} and the payload matches the historical lean shape, so existing
 * consumers are unaffected.
 *
 * Issue: CAP-247 Story #17; enriched fields: #828
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

    // -----------------------------------------------------------------------
    // Enriched fields — populated only when the search is requested with
    // detailed=true. Null/absent in the default lean response (#828).
    // -----------------------------------------------------------------------

    @Schema(
            description = "Lifecycle state; only populated for detailed search",
            example = "ACTIVE",
            implementation = ProductLifecycleState.class,
            nullable = true)
    private ProductLifecycleState lifecycleState;

    @Schema(
            description = "UTC instant the current lifecycle state took effect; only populated for detailed search",
            example = "2026-01-15T09:30:00Z",
            nullable = true)
    private Instant lifecycleStateEffectiveAt;

    @Schema(
            description = "Active MSRP amount; null when the product has no active MSRP or for lean search",
            example = "99.99",
            nullable = true)
    private String msrpAmount;

    @Schema(
            description = "Active MSRP ISO 4217 currency code; null when no active MSRP or for lean search",
            example = "USD",
            nullable = true)
    private String msrpCurrency;

    @Schema(
            description = "Start of the active MSRP effective window; null when no active MSRP or for lean search",
            example = "2026-01-15",
            nullable = true)
    private LocalDate msrpEffectiveStartDate;

    @Schema(
            description =
                    "End of the active MSRP effective window; null for open-ended, no active MSRP, or lean search",
            example = "2026-12-31",
            nullable = true)
    private LocalDate msrpEffectiveEndDate;
}
