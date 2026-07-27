package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.CostingMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Standard costing strategy (odoo-parity J1, issue #1048; ADR-0048).
 *
 * <p>Every entry is stamped with the SKU's configured standard price
 * ({@code standardCost}). When no standard price is configured the strategy
 * falls back to the latest receipt cost, then to {@code null} (uncosted). The
 * latest receipt cost is remembered in {@link CostState#avgCost()} (which under
 * STANDARD is a "latest receipt cost" memo, not a running average): a receipt
 * updates that memo but <strong>never</strong> the standard price itself —
 * purchase-price-variance handling is out of J1 scope, and setting/correcting
 * the standard price is the revaluation workflow (plan story J4).
 *
 * <p>The stamped value is rounded HALF_UP to the ledger's scale 4
 * ({@code inventory_ledger_entry.unit_cost}).
 */
@Component
public class StandardCostingStrategy implements CostingStrategy {

    private static final int STAMP_SCALE = 4;

    @Override
    public @NonNull CostingMethod method() {
        return CostingMethod.STANDARD;
    }

    @Override
    public @NonNull CostingResult cost(@NonNull CostingInput input) {
        BigDecimal standardCost = input.state().standardCost();
        int change = input.changeInQuantity();
        BigDecimal docCost = input.documentUnitCost();
        long newQty = input.state().onHandQty() + change;

        // avgCost doubles as the latest-receipt-cost memo under STANDARD.
        BigDecimal latestReceiptCost = input.state().avgCost();
        if (change > 0 && docCost != null) {
            latestReceiptCost = docCost; // remember most recent receipt cost; standard price untouched
        }

        BigDecimal toStamp = standardCost != null ? standardCost : latestReceiptCost;
        return new CostingResult(stamp(toStamp), new CostState(latestReceiptCost, newQty, standardCost));
    }

    private static @Nullable BigDecimal stamp(@Nullable BigDecimal value) {
        return value == null ? null : value.setScale(STAMP_SCALE, RoundingMode.HALF_UP);
    }
}
