package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.valuation.ValuationReportResponse;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Inventory valuation read model (odoo-parity J2, issue #1052).
 *
 * <p>Value = on-hand × current unit cost, where the unit cost is sourced from each SKU's running
 * {@code sku_cost_state} per its resolved costing method (odoo-parity J1). Quantities × cost are
 * doubly sensitive (DECISION-INVENTORY-011), so these reads are guarded by the dedicated
 * {@code inventory:valuation:view} permission — never exposed under {@code inventory:on_hand:view}
 * alone.
 */
public interface ValuationService {

    /**
     * Current on-hand valuation, optionally site- and SKU-scoped.
     *
     * @param locationId restrict to one site, or null for all sites
     * @param sku restrict to one SKU, or null/blank for every SKU with non-zero on-hand
     * @return SKU-level valuation rows and their total
     */
    @NonNull
    ValuationReportResponse getValuation(@Nullable UUID locationId, @Nullable String sku);

    /**
     * Point-in-time (as-of) on-hand valuation reconstructed from the ledger (pairs with odoo-parity
     * A3). On-hand is reconstructed by ledger replay to the instant; the unit cost is reconstructed
     * by replaying each SKU's on-hand-affecting entries through its costing strategy up to the same
     * instant. As-of history exposure additionally requires {@code inventory:ledger:view} and future
     * instants are rejected (handled by the shared as-of guard).
     *
     * @param locationId restrict to one site, or null for all sites
     * @param sku restrict to one SKU, or null/blank for every SKU with non-zero on-hand as of the instant
     * @param asOf the historical instant (inclusive)
     * @return SKU-level valuation rows and their total as of the instant
     */
    @NonNull
    ValuationReportResponse getValuationAsOf(@Nullable UUID locationId, @Nullable String sku, @NonNull Instant asOf);
}
