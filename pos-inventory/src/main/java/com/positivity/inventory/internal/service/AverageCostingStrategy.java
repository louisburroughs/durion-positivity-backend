package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.CostingMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Weighted-average (AVCO) costing strategy (odoo-parity J1, issue #1048;
 * ADR-0048; Odoo {@code _update_standard_price} mechanics).
 *
 * <ul>
 *   <li><b>Receipt</b> (positive change with a document unit cost): recompute
 *       the running average
 *       {@code newAvg = (oldAvg*oldQty + rcvQty*rcvUnitCost) / (oldQty + rcvQty)}
 *       and stamp the entry with the received document unit cost.</li>
 *   <li><b>Cost-less inbound</b> (positive change, no document cost — e.g. an
 *       adjustment-in or transfer-in that carries no cost): the goods enter at
 *       the current average; quantity rises, average unchanged; stamp the
 *       current average.</li>
 *   <li><b>Issue / consumption / scrap / transfer-out</b> (negative change):
 *       stamp the current average, decrement the quantity, leave the average
 *       unchanged.</li>
 * </ul>
 *
 * <p><strong>Negative branch (odoo-parity K1, issue #1027).</strong> When
 * on-hand is already at or below zero, an issue keeps the last known average
 * (it is stamped and left untouched — no divide-by-zero, no negative average).
 * A receipt onto a non-positive on-hand seeds the average from the receipt cost
 * rather than blending against a meaningless negative-quantity value.
 *
 * <p><strong>Rounding.</strong> The running average is held at scale 6; the
 * value stamped onto the ledger entry is rounded HALF_UP to the ledger's scale
 * 4 ({@code inventory_ledger_entry.unit_cost}, numeric(19,4)).
 */
@Component
public class AverageCostingStrategy implements CostingStrategy {

    private static final int AVG_SCALE = 6;
    private static final int STAMP_SCALE = 4;

    @Override
    public @NonNull CostingMethod method() {
        return CostingMethod.AVERAGE;
    }

    @Override
    public @NonNull CostingResult cost(@NonNull CostingInput input) {
        BigDecimal oldAvg = input.state().avgCost();
        BigDecimal oldQty = Quantities.nz(input.state().onHandQty());
        BigDecimal change = Quantities.nz(input.changeInQuantity());
        BigDecimal docCost = input.documentUnitCost();
        BigDecimal standardCost = input.state().standardCost();

        if (Quantities.isPositive(change) && docCost != null) {
            // Receipt: recompute weighted average, stamp the received cost.
            BigDecimal newAvg;
            if (!Quantities.isPositive(oldQty) || oldAvg == null) {
                // Seed (first receipt) or reset from a non-positive on-hand: no meaningful
                // prior value to blend — the receipt cost becomes the average.
                newAvg = docCost;
            } else {
                BigDecimal oldValue = oldAvg.multiply(oldQty);
                BigDecimal rcvValue = docCost.multiply(change);
                BigDecimal newQty = oldQty.add(change);
                newAvg = oldValue.add(rcvValue).divide(newQty, AVG_SCALE, RoundingMode.HALF_UP);
            }
            return new CostingResult(stamp(docCost), new CostState(newAvg, oldQty.add(change), standardCost));
        }

        if (Quantities.isPositive(change)) {
            // Cost-less inbound: enters at the current average; quantity rises, average unchanged.
            return new CostingResult(stamp(oldAvg), new CostState(oldAvg, oldQty.add(change), standardCost));
        }

        // Issue / consumption / scrap / transfer-out (and no-op zero change): stamp the current
        // average, decrement quantity, average unchanged. Negative branch (oldQty <= 0) keeps the
        // last known average by construction.
        return new CostingResult(stamp(oldAvg), new CostState(oldAvg, oldQty.add(change), standardCost));
    }

    private static @Nullable BigDecimal stamp(@Nullable BigDecimal value) {
        return value == null ? null : value.setScale(STAMP_SCALE, RoundingMode.HALF_UP);
    }
}
