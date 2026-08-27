package com.positivity.inventory.internal.config;

import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactResponse;
import com.positivity.inventory.service.SkuCategoryCutoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Says out loud, once per boot, what {@code pos.inventory.sku-category.resolve-from-replica}
 * is currently doing to costing (#1535).
 *
 * <p><strong>It informs; it never fails startup.</strong> After a completed
 * cut-over the SKU_CATEGORY rows are <em>supposed</em> to be reachable, so a
 * healthy deployment with the flag on is the normal case, not an alarm — this
 * reports how many SKUs the category step governs and reserves WARN for the one
 * condition that genuinely undermines the number, a report truncated by its own
 * cap.
 *
 * <p>With the flag off it asks only for a count. Building the full report to
 * read one integer would make every boot pay for a sequential scan of
 * {@code ext_product} to learn something a {@code COUNT(*)} answers.
 */
@Component
public class SkuCategoryCutoverStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkuCategoryCutoverStartupCheck.class);

    private static final String IMPACT_ENDPOINT = "GET /v1/inventory/valuation/methods/sku-category-impact";

    private final SkuCategoryCutoverService skuCategoryCutoverService;
    private final boolean resolveFromReplicaEnabled;

    public SkuCategoryCutoverStartupCheck(
            SkuCategoryCutoverService skuCategoryCutoverService,
            @Value("${pos.inventory.sku-category.resolve-from-replica:false}") boolean resolveFromReplicaEnabled) {
        this.skuCategoryCutoverService = skuCategoryCutoverService;
        this.resolveFromReplicaEnabled = resolveFromReplicaEnabled;
    }

    @Override
    @SuppressWarnings("java:S1181") // see catch block: an advisory log line must not be able to stop a boot
    public void run(ApplicationArguments args) {
        try {
            if (!resolveFromReplicaEnabled) {
                reportInertConfiguration();
            } else {
                reportActiveResolution();
            }
        } catch (Throwable t) {
            // Deliberately Throwable, not Exception. This class promises never to block startup, and
            // the realistic way to break that promise is an Error — an OutOfMemoryError from
            // materialising a large report — which `catch (Exception)` would let through. The report
            // is capped so this should be unreachable; the promise should not depend on that holding.
            log.warn("SKU_CATEGORY cut-over startup check did not complete: {}", t.toString());
        }
    }

    private void reportInertConfiguration() {
        long activeConfigs = skuCategoryCutoverService.activeSkuCategoryConfigCount();
        if (activeConfigs <= 0) {
            return;
        }
        log.info(
                "{} active SKU_CATEGORY costing method configuration row(s) are inert:"
                        + " pos.inventory.sku-category.resolve-from-replica is false, so costing resolution skips"
                        + " the SKU_CATEGORY step. Call {} to see what enabling it would change.",
                activeConfigs,
                IMPACT_ENDPOINT);
    }

    private void reportActiveResolution() {
        SkuCategoryImpactResponse impact = skuCategoryCutoverService.impact();

        if (impact.isTruncated()) {
            log.warn(
                    "pos.inventory.sku-category.resolve-from-replica is enabled and the impact scan hit its cap of"
                            + " {} products, so the SKU_CATEGORY counts below are lower bounds. Raise"
                            + " pos.inventory.sku-category.impact-sku-cap and re-run {} before relying on them.",
                    impact.getImpactSkuCap(),
                    IMPACT_ENDPOINT);
        }

        log.info(
                "pos.inventory.sku-category.resolve-from-replica is enabled: {} SKU(s) resolve their costing"
                        + " method from an active SKU_CATEGORY row, across {} configuration row(s). Audit with {}.",
                impact.getCategoryMatchedSkuCount(),
                impact.getActiveSkuCategoryConfigCount(),
                IMPACT_ENDPOINT);
    }
}
