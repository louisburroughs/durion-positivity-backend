package com.positivity.inventory.internal.config;

import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactResponse;
import org.jspecify.annotations.NonNull;

/**
 * Pre-flight audit for enabling SKU_CATEGORY resolution (#1535).
 *
 * <p>Answers one question: <em>which SKUs would change costing method, from
 * what to what, if I flip {@code pos.inventory.sku-category.resolve-from-replica}?</em>
 * — and answers it <strong>while the flag is still off</strong>, which is the
 * only moment at which the answer is worth anything. Once the flag is on the
 * change has already happened, silently and staggered per SKU by whenever
 * pos-catalog last republished each product.
 *
 * <p>It can answer with the flag off because it does not go through the
 * {@code SkuCategoryProvider} SPI the flag gates. It reads the {@code
 * ext_product} replica directly, and the replica is populated regardless of the
 * flag — the flag only decides whether costing and sourcing resolution are
 * allowed to consult it.
 *
 * <p>The report also covers sourcing, where SKU_CATEGORY is the
 * <em>highest</em>-precedence step (SKU_CATEGORY, then SITE, then DEFAULT, then
 * FIFO). Flipping the flag changes sourcing too, and that needs its own
 * sign-off. The cut-over procedure is in {@code docs/OPERATIONS_RUNBOOK.md}.
 */
public interface SkuCategoryCutoverService {

    /** The impact report for the current configuration and replica contents. */
    @NonNull
    SkuCategoryImpactResponse impact();

    /**
     * How many active SKU_CATEGORY costing configuration rows exist — the one number that says
     * whether the flag is worth thinking about at all, answered by a count query rather than by
     * building the whole report. The boot-time notice uses this when the flag is off.
     */
    long activeSkuCategoryConfigCount();
}
