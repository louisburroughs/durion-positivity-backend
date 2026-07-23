package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates missing {@code inventory_stock_summary} rows in a separate
 * transaction so a lost creation race (unique-key violation from a concurrent
 * posting) aborts only the inner transaction, never the caller's posting
 * transaction. {@code LedgerPostingServiceImpl} catches the violation,
 * re-acquires the row lock, and proceeds.
 */
@Component
public class StockSummaryRowInitializer {

    private final InventoryStockSummaryRepository summaryRepository;

    public StockSummaryRowInitializer(InventoryStockSummaryRepository summaryRepository) {
        this.summaryRepository = summaryRepository;
    }

    /**
     * Inserts a zero-quantity summary row for the key unless one already
     * exists. May throw a duplicate-key exception when racing another
     * posting; callers must treat that as "row now exists".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRowIfAbsent(@NonNull String stockItemId, @Nullable UUID locationId) {
        boolean exists = locationId == null
                ? summaryRepository
                        .findByStockItemIdAndLocationIdIsNull(stockItemId)
                        .isPresent()
                : summaryRepository
                        .findByStockItemIdAndLocationId(stockItemId, locationId)
                        .isPresent();
        if (exists) {
            return;
        }
        summaryRepository.saveAndFlush(InventoryStockSummary.builder()
                .stockItemId(stockItemId)
                .locationId(locationId)
                .onHand(0L)
                .allocated(0L)
                .reserved(0L)
                .atp(0L)
                .inTransitQty(0L)
                .build());
    }
}
