package com.positivity.inventory.internal.config;

import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactResponse;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactRow;
import com.positivity.inventory.service.SkuCategoryCutoverService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Says out loud, once per boot, what {@code pos.inventory.sku-category.resolve-from-replica}
 * is currently doing to costing (#1535).
 *
 * <p><strong>It warns; it never fails startup.</strong> The check cannot tell a
 * reconciled deployment from an un-reconciled one: after a legitimate cut-over
 * the SKU_CATEGORY rows are <em>supposed</em> to be reachable and the impacted
 * set is supposed to be exactly the SKUs that were deliberately revalued. A
 * check that cannot distinguish the intended end state from the accident must
 * not hold a veto over the service booting. The whole body is wrapped like
 * {@link EventTypeInitializer}'s registration: it swallows everything and
 * rethrows nothing, because an advisory audit line is never worth an outage.
 */
@Component
public class SkuCategoryCutoverStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkuCategoryCutoverStartupCheck.class);

    /** Enough ids to start an investigation with; the endpoint has the full list. */
    private static final int MAX_LOGGED_STOCK_ITEM_IDS = 20;

    private static final String IMPACT_ENDPOINT = "GET /v1/inventory/valuation/methods/sku-category-impact";

    private final SkuCategoryCutoverService skuCategoryCutoverService;

    public SkuCategoryCutoverStartupCheck(SkuCategoryCutoverService skuCategoryCutoverService) {
        this.skuCategoryCutoverService = skuCategoryCutoverService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            SkuCategoryImpactResponse impact = skuCategoryCutoverService.impact();

            if (!impact.isResolveFromReplicaEnabled()) {
                if (impact.getActiveSkuCategoryConfigCount() > 0) {
                    log.info(
                            "{} active SKU_CATEGORY costing method configuration row(s) are inert:"
                                    + " pos.inventory.sku-category.resolve-from-replica is false, so costing"
                                    + " resolution skips the SKU_CATEGORY step. Call {} to see what enabling it"
                                    + " would change.",
                            impact.getActiveSkuCategoryConfigCount(),
                            IMPACT_ENDPOINT);
                }
                return;
            }

            if (impact.getImpactedSkuCount() > 0) {
                log.warn(
                        "pos.inventory.sku-category.resolve-from-replica is ENABLED and {} SKU(s) resolve a"
                                + " different costing method under the SKU_CATEGORY step than they would"
                                + " otherwise. First {}: {}. If this was not a completed cut-over, see"
                                + " \"SKU_CATEGORY costing and sourcing cut-over (#1535)\" in"
                                + " docs/OPERATIONS_RUNBOOK.md and audit with {}.",
                        impact.getImpactedSkuCount(),
                        MAX_LOGGED_STOCK_ITEM_IDS,
                        loggedStockItemIds(impact.getImpactedSkus()),
                        IMPACT_ENDPOINT);
            } else {
                log.info("pos.inventory.sku-category.resolve-from-replica is enabled and no SKU resolves a"
                        + " different costing method under SKU_CATEGORY.");
            }
        } catch (Exception e) {
            log.warn("SKU_CATEGORY cut-over startup check did not complete: {}", e.toString());
        }
    }

    private static List<String> loggedStockItemIds(List<SkuCategoryImpactRow> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .limit(MAX_LOGGED_STOCK_ITEM_IDS)
                .map(SkuCategoryImpactRow::getStockItemId)
                .toList();
    }
}
