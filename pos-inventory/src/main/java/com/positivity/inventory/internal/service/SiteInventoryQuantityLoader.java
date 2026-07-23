package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads bulk per-location quantity maps inside a single read-only
 * transaction, keeping the remote topology fetch outside transactional
 * scope (CAP-218 #658; see #657 handoff note on non-atomic grouped sums).
 *
 * <p>Since issue #1024 (A1) quantities come from the
 * {@code inventory_stock_summary} read model rather than ledger aggregation.
 */
@Component
public class SiteInventoryQuantityLoader {

    private final InventoryStockSummaryRepository stockSummaryRepository;

    public SiteInventoryQuantityLoader(InventoryStockSummaryRepository stockSummaryRepository) {
        this.stockSummaryRepository = stockSummaryRepository;
    }

    /**
     * On-hand and outstanding-allocation quantities per location.
     *
     * @param sku optional stock item filter; null/blank = all stock items
     */
    @Transactional(readOnly = true)
    @NonNull
    public QuantitySnapshot load(@NonNull Collection<UUID> locationIds, @Nullable String sku) {
        String skuFilter = sku == null || sku.isBlank() ? null : sku;
        InventoryStockSummaryRepository.LocationQuantityMaps maps =
                stockSummaryRepository.sumByLocationChunked(locationIds, skuFilter);
        return new QuantitySnapshot(maps.onHand(), maps.allocated());
    }

    public record QuantitySnapshot(Map<UUID, Long> onHand, Map<UUID, Long> allocated) {}
}
