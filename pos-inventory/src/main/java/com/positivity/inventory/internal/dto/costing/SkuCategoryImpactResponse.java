package com.positivity.inventory.internal.dto.costing;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.CostingMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pre-flight report for enabling SKU_CATEGORY resolution (#1535): what flipping
 * {@code pos.inventory.sku-category.resolve-from-replica} would do to costing
 * and to sourcing, computed while the flag is still off.
 *
 * <p>{@code resolveFromReplicaEnabled} is the report's premise, not a
 * suggestion: read it first. While it is false the impacted rows are what
 * <em>would</em> change; once it is true they are what is <em>already</em>
 * changing, and a non-empty list then means the cut-over was not finished.
 */
@Schema(description = "Impact of enabling SKU_CATEGORY costing and sourcing resolution (#1535)")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkuCategoryImpactResponse {

    @Schema(
            description = "Current value of pos.inventory.sku-category.resolve-from-replica — the premise of this"
                    + " report. False means the impacted rows are pending; true means they have already taken"
                    + " effect.",
            example = "false",
            requiredMode = REQUIRED)
    private boolean resolveFromReplicaEnabled;

    @Schema(
            description = "Deployment default pos.inventory.valuation.default-method, the last fallback",
            example = "AVERAGE",
            requiredMode = REQUIRED)
    private CostingMethod deploymentDefaultMethod;

    @Schema(
            description = "Method of the active DEFAULT configuration row, or null when there is none",
            example = "AVERAGE",
            requiredMode = NOT_REQUIRED)
    private CostingMethod activeDefaultConfigMethod;

    @Schema(
            description = "Number of active SKU_CATEGORY costing configuration rows, inert or not",
            example = "3",
            requiredMode = REQUIRED)
    private int activeSkuCategoryConfigCount;

    @Schema(
            description = "Configured category names that match no replicated product, sorted. Usually a casing or"
                    + " spelling mismatch against ext_product.category_name: matching is exact and"
                    + " case-sensitive after trimming, so such a row would silently never fire.",
            example = "[\"electrical system\"]",
            requiredMode = REQUIRED)
    private List<String> categoriesWithNoReplicatedProducts;

    @Schema(
            description = "SKUs reached by an active SKU_CATEGORY row, before shielding and no-op filtering",
            example = "120",
            requiredMode = REQUIRED)
    private int evaluatedSkuCount;

    @Schema(description = "SKUs whose costing method would actually change", example = "17", requiredMode = REQUIRED)
    private int impactedSkuCount;

    @Schema(
            description = "Impacted SKUs that already have a sku_cost_state row, i.e. the ones a J4 revaluation"
                    + " cut-over has to cover",
            example = "12",
            requiredMode = REQUIRED)
    private int impactedSkuWithCostStateCount;

    @Schema(description = "The impacted SKUs, one row each", requiredMode = REQUIRED)
    private List<SkuCategoryImpactRow> impactedSkus;

    @Schema(
            description = "SKUs whose sourcing strategy would start resolving from a SKU_CATEGORY row — a separate"
                    + " change from costing, needing its own sign-off",
            requiredMode = REQUIRED)
    private List<SourcingImpactRow> impactedSourcingSkus;
}
