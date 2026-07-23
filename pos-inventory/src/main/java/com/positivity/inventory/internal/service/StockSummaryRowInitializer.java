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
     * Inserts a zero-quantity summary row for the (stockItemId, locationId,
     * lotId) key unless one already exists ({@code lotId} null = the
     * lot-agnostic row; odoo-parity E1, #1038). May throw a duplicate-key
     * exception when racing another posting; callers must treat that as "row
     * now exists".
     *
     * <p>Callers must serialize invocations per JVM (see
     * {@code LedgerPostingServiceImpl}'s creation monitor): since E1 every key
     * contains a NULL {@code lot_id} on its lot-agnostic row, and only
     * PostgreSQL's {@code NULLS NOT DISTINCT} unique index (V18) can reject a
     * duplicate such key — the JPA-generated H2 test schema cannot. Across
     * instances the PostgreSQL index remains the backstop.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRowIfAbsent(@NonNull String stockItemId, @Nullable UUID locationId, @Nullable UUID lotId) {
        if (summaryRepository.findByKey(stockItemId, locationId, lotId).isPresent()) {
            return;
        }
        summaryRepository.saveAndFlush(InventoryStockSummary.builder()
                .stockItemId(stockItemId)
                .locationId(locationId)
                .lotId(lotId)
                .onHand(0L)
                .allocated(0L)
                .reserved(0L)
                .atp(0L)
                .inTransitQty(0L)
                .build());
    }
}
