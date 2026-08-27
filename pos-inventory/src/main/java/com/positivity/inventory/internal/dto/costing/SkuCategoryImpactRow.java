package com.positivity.inventory.internal.dto.costing;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.CostingMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One SKU whose costing method would change if
 * {@code pos.inventory.sku-category.resolve-from-replica} were enabled (#1535).
 *
 * <p>Only SKUs whose {@code projectedMethod} actually differs from their
 * {@code currentMethod} appear here — a category override that happens to name
 * the method the SKU already resolves is a no-op and would only drown the rows
 * that matter. The counts on {@link SkuCategoryImpactResponse} still describe
 * the whole evaluated population.
 *
 * <p>{@code onHandQty}, {@code avgCost} and {@code standardCost} are null when
 * the SKU has no {@code sku_cost_state} row yet — nothing has been costed, so
 * there is no opening value to restate and the J4 revaluation step does not
 * apply to it.
 */
@Schema(description = "One SKU whose costing method would change under SKU_CATEGORY resolution (#1535)")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkuCategoryImpactRow {

    @Schema(
            description = "Stock item id the costing engine resolves against",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
            requiredMode = REQUIRED)
    private String stockItemId;

    @Schema(
            description = "Catalog product id in the ext_product replica; identical to stockItemId for"
                    + " product-backed SKUs",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
            requiredMode = REQUIRED)
    private UUID productId;

    @Schema(
            description = "Replicated catalog category id; the rename-stable key",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60",
            requiredMode = NOT_REQUIRED)
    private UUID categoryId;

    @Schema(
            description = "Replicated catalog category name, the value SKU_CATEGORY config rows are matched on",
            example = "Electrical System",
            requiredMode = REQUIRED)
    private String categoryName;

    @Schema(
            description = "Id of the SKU_CATEGORY configuration row that would win",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61",
            requiredMode = REQUIRED)
    private UUID configId;

    @Schema(
            description = "Method this SKU resolves today (active DEFAULT config, else the deployment default)",
            example = "AVERAGE",
            requiredMode = REQUIRED)
    private CostingMethod currentMethod;

    @Schema(
            description = "Method this SKU would resolve once SKU_CATEGORY resolution is enabled",
            example = "STANDARD",
            requiredMode = REQUIRED)
    private CostingMethod projectedMethod;

    @Schema(
            description = "Whether the SKU has a sku_cost_state row, i.e. whether it has opening values a J4"
                    + " revaluation would have to restate",
            example = "true",
            requiredMode = REQUIRED)
    private boolean hasCostState;

    @Schema(
            description = "Quantity the costing engine has costed so far; null when there is no cost state",
            example = "42.0000",
            requiredMode = NOT_REQUIRED)
    private BigDecimal onHandQty;

    @Schema(
            description = "Running weighted-average unit cost; null when there is no cost state",
            example = "12.500000",
            requiredMode = NOT_REQUIRED)
    private BigDecimal avgCost;

    @Schema(
            description = "Configured standard price; null when there is no cost state or none is set",
            example = "13.000000",
            requiredMode = NOT_REQUIRED)
    private BigDecimal standardCost;
}
