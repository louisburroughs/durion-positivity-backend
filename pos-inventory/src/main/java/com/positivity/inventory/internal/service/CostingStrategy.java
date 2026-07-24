package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Pluggable costing method (odoo-parity J1, issue #1048; ADR-0048). A strategy
 * is a pure function over one on-hand-affecting ledger posting: given the SKU's
 * current running cost state and the movement (event type, signed quantity, and
 * the document unit cost for receipts), it returns the {@code unitCost} to stamp
 * on THIS ledger entry plus the SKU's updated running cost state. The single
 * ledger posting funnel resolves the method per SKU, invokes the matching
 * strategy in batch order, stamps the entry, and persists the returned state —
 * all in the ledger append transaction.
 *
 * <p><strong>FIFO readiness (ADR-0048 v2).</strong> The signature is
 * deliberately state-in → (unitCost, state-out): FIFO's per-receipt
 * remaining-qty/remaining-value layer consumption is equally a pure function of
 * (state, event, quantity, document cost). A v2 {@code FifoCostingStrategy}
 * implements this same interface; its richer layer stack is carried in an
 * extended {@link CostState} (and/or a companion layer side-table keyed by
 * ledger entry id) without changing this method or the funnel wiring. v1 ships
 * STANDARD and AVERAGE only.
 */
public interface CostingStrategy {

    /** The method this strategy implements; the resolver keys strategies by it. */
    @NonNull
    CostingMethod method();

    /** Computes the unit cost for one entry and the SKU's next running cost state. */
    @NonNull
    CostingResult cost(@NonNull CostingInput input);

    /**
     * Input to a costing computation for a single on-hand-affecting ledger entry.
     *
     * @param stockItemId the SKU being moved
     * @param eventType the ledger event type (always on-hand-affecting when the funnel calls a strategy)
     * @param changeInQuantity signed movement quantity — positive inbound (receipt), negative outbound (issue)
     * @param documentUnitCost the source-document unit cost carried on the entry for receipts; null otherwise
     * @param state the SKU's current running cost state (never null; a freshly seeded zero state for a new SKU)
     */
    record CostingInput(
            @NonNull String stockItemId,
            @NonNull InventoryLedgerEventType eventType,
            int changeInQuantity,
            @Nullable BigDecimal documentUnitCost,
            @NonNull CostState state) {}

    /**
     * Output of a costing computation.
     *
     * @param unitCost the method-derived unit cost to stamp on the entry, or null when uncosted
     *     (e.g. a first issue with no prior cost — visibly uncosted, matching pre-J1 behaviour)
     * @param state the SKU's updated running cost state to persist
     */
    record CostingResult(
            @Nullable BigDecimal unitCost, @NonNull CostState state) {}

    /**
     * Immutable snapshot of a SKU's running cost state, mirrored from
     * {@code sku_cost_state}. {@code avgCost} is the running weighted average
     * (AVERAGE) or the latest-receipt-cost memo (STANDARD); {@code onHandQty}
     * is the quantity costed so far (AVCO denominator and negative-branch
     * input); {@code standardCost} is the configured standard price (STANDARD),
     * null until set via revaluation (J4).
     */
    record CostState(
            @Nullable BigDecimal avgCost,
            long onHandQty,
            @Nullable BigDecimal standardCost) {}
}
