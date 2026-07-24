package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the empty {@code sku_cost_state} master row for a previously-unseen SKU in a
 * {@code REQUIRES_NEW} transaction (odoo-parity J1, issue #1048), mirroring
 * {@link StockSummaryRowInitializer} / {@link InventoryLotCreator}: a lost race on the
 * {@code stock_item_id} unique index aborts only this inner transaction, so the funnel's
 * costing pass ({@link LedgerCostingService}) can re-read the winner's row without its own
 * posting transaction being poisoned. Separate bean because Spring's transactional proxying
 * does not apply to self-invocation.
 *
 * <p>The seed row carries no cost ({@code avgCost}/{@code standardCost} null, {@code onHandQty}
 * 0), so a seed that survives a rolled-back posting is harmless and idempotent — the running
 * cost is only ever advanced by a committed costing pass.
 */
@Component
public class SkuCostStateInitializer {

    private final SkuCostStateRepository costStateRepository;

    public SkuCostStateInitializer(SkuCostStateRepository costStateRepository) {
        this.costStateRepository = costStateRepository;
    }

    /**
     * Inserts a zero/uncosted state row for {@code stockItemId} unless one already exists.
     * A concurrent first insert of the same SKU loses the {@code stock_item_id} unique-index
     * race here and is swallowed — the winner's committed row is what the caller re-reads.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRowIfAbsent(@NonNull String stockItemId) {
        if (costStateRepository.findByStockItemId(stockItemId).isPresent()) {
            return;
        }
        try {
            costStateRepository.saveAndFlush(SkuCostState.builder()
                    .stockItemId(stockItemId)
                    .avgCost(null)
                    .onHandQty(0L)
                    .standardCost(null)
                    .build());
        } catch (DataIntegrityViolationException raced) {
            // Concurrent first insert of the same SKU won the race; its row is committed.
        }
    }
}
