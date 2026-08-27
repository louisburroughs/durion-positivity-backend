package com.positivity.inventory.internal.dto.costing;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.SourcingStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One SKU whose sourcing strategy would start resolving from a SKU_CATEGORY
 * configuration row if {@code pos.inventory.sku-category.resolve-from-replica}
 * were enabled (#1535).
 *
 * <p>Sourcing is the half of the flag that is easy to overlook, and it is the
 * more sweeping half: SKU_CATEGORY is the <strong>highest</strong>-precedence
 * step in sourcing resolution (SKU_CATEGORY, then SITE, then DEFAULT, then
 * FIFO), so a category row that starts resolving overrides even a deliberate
 * per-site strategy.
 *
 * <p><strong>Today's effective strategy is deliberately not reported.</strong>
 * {@code SourcingStrategyServiceImpl.resolveStrategy} needs a
 * {@code SourcingSelection} — a site id and a reference location — to compute
 * it, and this report has no selection to hand it: the question "what does this
 * SKU source as right now" has a different answer per site. Guessing one, or
 * quietly reporting the DEFAULT row as though it were the answer, would be
 * worse than omitting the field, because it would be believed. Read
 * {@code projectedStrategy} against the sourcing configuration you already know
 * and sign the sourcing change off separately.
 */
@Schema(description = "One SKU whose sourcing strategy would start resolving from SKU_CATEGORY (#1535)")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourcingImpactRow {

    @Schema(
            description = "Stock item id the sourcing engine resolves against",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
            requiredMode = REQUIRED)
    private String stockItemId;

    @Schema(
            description = "Catalog product id in the ext_product replica",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
            requiredMode = REQUIRED)
    private UUID productId;

    @Schema(
            description = "Replicated catalog category name the SKU_CATEGORY row is matched on",
            example = "Electrical System",
            requiredMode = REQUIRED)
    private String categoryName;

    @Schema(
            description = "Id of the SKU_CATEGORY sourcing configuration row that would win",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a62",
            requiredMode = REQUIRED)
    private UUID configId;

    @Schema(
            description = "Strategy this SKU would source by once SKU_CATEGORY resolution is enabled",
            example = "FEFO",
            requiredMode = REQUIRED)
    private SourcingStrategy projectedStrategy;
}
