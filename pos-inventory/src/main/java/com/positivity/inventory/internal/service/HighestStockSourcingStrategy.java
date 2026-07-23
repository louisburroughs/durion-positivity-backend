package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * HIGHEST_STOCK ordering (odoo-parity H1, issue #1037): candidate locations
 * ordered by available quantity ({@code onHand - allocated}), most first.
 *
 * <p>Availability comes from the candidate itself when the caller provided
 * it, otherwise from the {@code inventory_stock_summary} read model; SKUs
 * without a summary row at a location count as zero available.
 *
 * <p>Tie-breaker (deterministic): equal availability orders by ascending
 * location id.
 */
@Component
@RequiredArgsConstructor
public class HighestStockSourcingStrategy implements SourcingOrderingStrategy {

    private final InventoryStockSummaryRepository stockSummaryRepository;

    @Override
    public @NonNull SourcingStrategy strategy() {
        return SourcingStrategy.HIGHEST_STOCK;
    }

    @Override
    public @NonNull List<SourcingCandidate> order(@NonNull SourcingSelection selection) {
        if (selection.candidates().size() < 2) {
            return List.copyOf(selection.candidates());
        }
        return selection.candidates().stream()
                .sorted(Comparator.comparingLong(
                                (SourcingCandidate candidate) -> -availableOf(selection.stockItemId(), candidate))
                        .thenComparing(SourcingCandidate::locationId))
                .toList();
    }

    private long availableOf(String stockItemId, SourcingCandidate candidate) {
        if (candidate.availableQuantity() != null) {
            return candidate.availableQuantity();
        }
        return stockSummaryRepository
                .findByStockItemIdAndLocationId(stockItemId, candidate.locationId())
                .map(summary -> summary.getOnHand() - summary.getAllocated())
                .orElse(0L);
    }
}
