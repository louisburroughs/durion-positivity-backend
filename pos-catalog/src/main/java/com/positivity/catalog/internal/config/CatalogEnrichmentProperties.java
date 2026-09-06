package com.positivity.catalog.internal.config;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment tuning for MKCAT enrichment matching (#1645).
 *
 * <h2>Why the thresholds are configuration</h2>
 *
 * The right cut-off depends on how consistently a deployment's vendors name things, which is not
 * knowable from the code. The defaults below are the ones ADR-0060 records: {@code 0.80} to attach
 * without asking, {@code 0.50} — #1352's single threshold — as the floor below which a candidate is
 * not worth a reviewer's time. Nothing that matched under #1352 stops being considered; some of it
 * moves from silently attached to parked for review, which is the whole point.
 *
 * <h2>Why the brand aliases are configuration rather than a table</h2>
 *
 * A brand alias ("Michelin North America" is Michelin) is a fact about the vendor's spelling
 * habits, not about the catalogue, and it changes when a vendor feed changes — which is a
 * deployment event, not a business transaction. Keeping it in YAML means it ships and rolls back
 * with the deployment that needed it, needs no admin surface, and cannot drift per environment
 * without someone seeing the diff. The cost, accepted deliberately: an operator cannot add one at
 * runtime.
 *
 * <p>Example:
 *
 * <pre>{@code
 * pos:
 *   catalog:
 *     enrichment:
 *       auto-threshold: 0.80
 *       review-threshold: 0.50
 *       brand-aliases:
 *         michelinnorthamerica: Michelin
 *         contigroup: Continental
 * }</pre>
 *
 * @param autoThreshold score at or above which an unambiguous candidate is attached without asking;
 *     null = 0.80
 * @param reviewThreshold score at or above which a candidate is shown to a reviewer; null = 0.50
 * @param brandAliases normalised alias (see {@code BrandNormalizer}) to canonical brand. Keys are
 *     matched after normalisation, so {@code michelinnorthamerica} catches "Michelin North America",
 *     "MICHELIN NORTH AMERICA," and "Michelin North America, Inc." alike
 */
@ConfigurationProperties(prefix = "pos.catalog.enrichment")
public record CatalogEnrichmentProperties(
        @Nullable Double autoThreshold,
        @Nullable Double reviewThreshold,
        @Nullable Map<String, String> brandAliases) {

    /** ADR-0060 default: close enough that attaching without asking is safer than a review queue. */
    public static final double DEFAULT_AUTO_THRESHOLD = 0.80;

    /** ADR-0060 default: #1352's single threshold, demoted to "worth a person's attention". */
    public static final double DEFAULT_REVIEW_THRESHOLD = 0.50;

    public double autoThresholdOrDefault() {
        return autoThreshold != null ? autoThreshold : DEFAULT_AUTO_THRESHOLD;
    }

    public double reviewThresholdOrDefault() {
        return reviewThreshold != null ? reviewThreshold : DEFAULT_REVIEW_THRESHOLD;
    }

    public Map<String, String> brandAliasesOrEmpty() {
        return brandAliases != null ? brandAliases : Map.of();
    }
}
