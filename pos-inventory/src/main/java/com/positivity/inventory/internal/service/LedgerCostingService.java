package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Method-derived cost stamping inside the single ledger posting funnel
 * (odoo-parity J1, issue #1048; ADR-0048). Invoked by
 * {@code LedgerPostingServiceImpl} within the ledger append transaction: for
 * every on-hand-affecting entry in batch order it resolves the SKU's costing
 * method, invokes the matching {@link CostingStrategy} to compute and stamp
 * {@code unitCost}, and updates the SKU's {@code sku_cost_state} running cost.
 *
 * <p><strong>Batch order matters.</strong> Entries are processed in the order
 * the caller batched them, so a receipt then an issue for the same SKU in one
 * batch updates the running cost first, then the issue stamps it. Per-SKU state
 * is cached across the batch so intra-batch dependencies see the updated value;
 * all touched states are persisted once at the end (same transaction).
 *
 * <p><strong>Scope.</strong> Only {@link InventoryLedgerEventType#affectsOnHand()
 * on-hand-affecting} entries are costed. Allocation, reservation, backorder, and
 * pick-task markers get no cost. Cost is per-SKU, not per-lot: lot-tagged entries
 * cost against the SKU's single running state (per-lot valuation is an ADR-0048
 * non-goal). A first issue for a SKU with no prior cost stamps {@code null}
 * (visibly uncosted, matching pre-J1 behaviour).
 */
@Service
public class LedgerCostingService {

    private final CostingMethodResolver methodResolver;
    private final SkuCostStateRepository costStateRepository;
    private final Map<CostingMethod, CostingStrategy> strategies;

    public LedgerCostingService(
            CostingMethodResolver methodResolver,
            SkuCostStateRepository costStateRepository,
            List<CostingStrategy> costingStrategies) {
        this.methodResolver = methodResolver;
        this.costStateRepository = costStateRepository;
        this.strategies = new EnumMap<>(CostingMethod.class);
        for (CostingStrategy strategy : costingStrategies) {
            this.strategies.put(strategy.method(), strategy);
        }
    }

    /**
     * Stamps a method-derived {@code unitCost} on every on-hand-affecting entry
     * in the batch (in order) and updates each SKU's running cost state. Called
     * inside the funnel transaction after the ledger entries are persisted.
     */
    public void stampCosts(@NonNull List<InventoryLedgerEntry> entries) {
        Map<String, CostingMethod> methodCache = new HashMap<>();
        Map<String, SkuCostState> stateCache = new LinkedHashMap<>();

        for (InventoryLedgerEntry entry : entries) {
            InventoryLedgerEventType eventType = entry.getEventType();
            if (eventType == null || !eventType.affectsOnHand()) {
                continue; // allocation/reservation/backorder/pick-task markers carry no cost
            }
            String stockItemId = entry.getStockItemId();
            CostingMethod method = methodCache.computeIfAbsent(stockItemId, methodResolver::resolve);
            CostingStrategy strategy = strategies.get(method);
            if (strategy == null) {
                throw new IllegalStateException("No costing strategy registered for method " + method);
            }
            SkuCostState state = stateCache.computeIfAbsent(stockItemId, this::loadOrSeedState);

            int change = entry.getChangeInQuantity() == null ? 0 : entry.getChangeInQuantity();
            CostingStrategy.CostState currentState =
                    new CostingStrategy.CostState(state.getAvgCost(), state.getOnHandQty(), state.getStandardCost());
            CostingStrategy.CostingInput input =
                    new CostingStrategy.CostingInput(stockItemId, eventType, change, entry.getUnitCost(), currentState);

            CostingStrategy.CostingResult result = strategy.cost(input);

            entry.setUnitCost(result.unitCost());
            applyState(state, result.state());
        }

        if (!stateCache.isEmpty()) {
            costStateRepository.saveAll(stateCache.values());
        }
    }

    private SkuCostState loadOrSeedState(String stockItemId) {
        return costStateRepository
                .findByStockItemId(stockItemId)
                .orElseGet(() -> SkuCostState.builder()
                        .stockItemId(stockItemId)
                        .avgCost(null)
                        .onHandQty(0L)
                        .standardCost(null)
                        .build());
    }

    private void applyState(SkuCostState state, CostingStrategy.CostState next) {
        BigDecimal avg = next.avgCost();
        state.setAvgCost(avg);
        state.setOnHandQty(next.onHandQty());
        state.setStandardCost(next.standardCost());
    }
}
