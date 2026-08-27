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
 * and to sourcing.
 *
 * <p>{@code resolveFromReplicaEnabled} is an <strong>input to the
 * computation</strong>, not a label on it. Each row's {@code currentMethod} is
 * resolved the way the running system would resolve it under that flag: with the
 * flag off a matched SKU takes the global fallback, so a differing category
 * method shows up as impact; with the flag on that same SKU already resolves
 * from its category, so current and projected agree and it drops out.
 * {@code impactedSkuCount} therefore means "changes still pending" and reaches
 * zero once the flip is complete, instead of returning the same number forever.
 * Note the corollary: with the flag on it is zero <em>by construction</em>, so
 * it is a planning figure, not a post-flip audit.
 *
 * <p>For the standing question "how many SKUs does the category step govern",
 * read {@code categoryMatchedSkuCount}, which is meaningful under either flag
 * state.
 *
 * <p>When {@code truncated} is true the row lists and every count derived from
 * them are <strong>lower bounds</strong>; only {@code evaluatedSkuCount} and the
 * two category-name diagnostics stay exact. Raise {@code impactSkuCap} and
 * re-run before treating a truncated report as a decision record.
 */
@Schema(description = "Impact of enabling SKU_CATEGORY costing and sourcing resolution (#1535)")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkuCategoryImpactResponse {

    @Schema(
            description = "Current value of pos.inventory.sku-category.resolve-from-replica. An input to the"
                    + " calculation, not a label: each row's currentMethod is resolved as the running system"
                    + " would resolve it under this flag, so impactedSkuCount means changes still pending and"
                    + " reaches zero once the cut-over is complete.",
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
            description = "Number of active SKU_CATEGORY costing configuration rows",
            example = "3",
            requiredMode = REQUIRED)
    private int activeSkuCategoryConfigCount;

    @Schema(
            description = "Configured category names matching no replicated product, sorted. Usually a casing or"
                    + " spelling mismatch against ext_product.category_name: matching is exact and"
                    + " case-sensitive, so such a row silently never fires. Computed over the whole ext_product"
                    + " table, so it stays exact even when truncated is true.",
            example = "[\"electrical system\"]",
            requiredMode = REQUIRED)
    private List<String> categoriesWithNoReplicatedProducts;

    @Schema(
            description = "Configured scope values carrying leading or trailing whitespace, sorted. These can"
                    + " never fire: resolution keys on the stored scope value verbatim while the category side is"
                    + " trimmed, so the two can never be equal. The admin API trims on write, so these are seeded"
                    + " or hand-inserted rows. Fix them before reading the counts as final.",
            example = "[\"  Beverages  \"]",
            requiredMode = REQUIRED)
    private List<String> categoriesWithUntrimmedScopeValue;

    @Schema(
            description = "SKUs reached by an active SKU_CATEGORY costing row, before shielding and no-op"
                    + " filtering. Answered by a count query, so exact even when truncated is true.",
            example = "120",
            requiredMode = REQUIRED)
    private long evaluatedSkuCount;

    @Schema(
            description = "Unshielded SKUs matched by an active SKU_CATEGORY costing row — the SKUs this flag"
                    + " governs. With the flag on these resolve from their category today; with it off they"
                    + " would. Unlike impactedSkuCount this does not go to zero after the cut-over. A lower"
                    + " bound when truncated is true.",
            example = "94",
            requiredMode = REQUIRED)
    private int categoryMatchedSkuCount;

    @Schema(
            description = "SKUs whose costing method would still change — changes not yet cut over. Zero by"
                    + " construction whenever resolveFromReplicaEnabled is true, since a matched SKU then"
                    + " already resolves from its category: this field is for planning the flip, not for"
                    + " auditing it afterwards. A lower bound when truncated is true.",
            example = "17",
            requiredMode = REQUIRED)
    private int impactedSkuCount;

    @Schema(
            description = "Impacted SKUs that already have a sku_cost_state row, i.e. the ones a J4 revaluation"
                    + " cut-over has to cover. A lower bound when truncated is true.",
            example = "12",
            requiredMode = REQUIRED)
    private int impactedSkuWithCostStateCount;

    @Schema(description = "The impacted SKUs, one row each", requiredMode = REQUIRED)
    private List<SkuCategoryImpactRow> impactedSkus;

    @Schema(
            description = "SKUs whose sourcing strategy resolves, or would resolve, from a SKU_CATEGORY row — a"
                    + " separate change from costing, needing its own sign-off",
            requiredMode = REQUIRED)
    private List<SourcingImpactRow> impactedSourcingSkus;

    @Schema(
            description = "True when the product scan hit impactSkuCap and the row lists are incomplete. Every"
                    + " count derived from rows is then a lower bound. Raise the cap and re-run before using a"
                    + " truncated report to decide a financial cut-over.",
            example = "false",
            requiredMode = REQUIRED)
    private boolean truncated;

    @Schema(
            description = "The pos.inventory.sku-category.impact-sku-cap in force for this run",
            example = "5000",
            requiredMode = REQUIRED)
    private int impactSkuCap;
}
