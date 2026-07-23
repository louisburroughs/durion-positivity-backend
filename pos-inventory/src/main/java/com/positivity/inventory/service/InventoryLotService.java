package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.lot.LotDetailResponse;
import com.positivity.inventory.internal.dto.lot.LotResponse;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read API over the lot master and its per-lot stock balances (odoo-parity E1, issue #1038).
 *
 * <p>Lots are created exclusively by the inbound receipt paths (find-or-create per
 * (stockItemId, lotNumber)); this service only reads. Lot on-hand comes from the per-lot
 * {@code inventory_stock_summary} rows the posting funnel maintains next to the lot-agnostic
 * ones.
 */
public interface InventoryLotService {

    /**
     * Lists lot master records matching the optional filters, newest received first.
     *
     * @param stockItemId optional stock item filter
     * @param status optional lifecycle status filter
     * @param lotNumber optional exact lot-number filter
     * @return matching lots
     */
    @NonNull
    List<LotResponse> listLots(
            @Nullable String stockItemId, @Nullable InventoryLotStatus status, @Nullable String lotNumber);

    /**
     * One lot with its per-location on-hand balances.
     *
     * @param lotId the lot id
     * @return the lot detail
     */
    @NonNull
    LotDetailResponse getLot(@NonNull UUID lotId);
}
