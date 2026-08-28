package com.positivity.inventory.internal.tracking.service;

import com.positivity.inventory.internal.dto.lot.LotDetailResponse;
import com.positivity.inventory.internal.dto.lot.LotExpirationUpdateRequest;
import com.positivity.inventory.internal.dto.lot.LotResponse;
import com.positivity.inventory.internal.dto.lot.LotStatusUpdateRequest;
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

    /**
     * Changes a lot's lifecycle status (odoo-parity E3, issue #1047): quarantine, recall, or
     * release back to ACTIVE. This is a status flip only — QUARANTINED/RECALLED lots are blocked
     * from lot suggestion and outbound movement, but physically moving the stock to the site's
     * quarantine bin remains an operator-driven transfer (the {@code defaultQuarantineLocationId}
     * on the site is the documented destination for that separate flow). {@code CONSUMED} is not
     * settable here (reconciler-owned).
     *
     * @param lotId the lot id
     * @param request the target status and mandatory reason
     * @return the updated lot
     */
    @NonNull
    LotResponse updateStatus(@NonNull UUID lotId, @NonNull LotStatusUpdateRequest request);

    /**
     * Sets or clears a lot's expiration and alert-window dates (odoo-parity E3, issue #1047).
     * Re-dating clears the emit-once alert bookkeeping so the daily scan can alert the new dates.
     *
     * @param lotId the lot id
     * @param request the expiration and alert dates (either may be null to clear)
     * @return the updated lot
     */
    @NonNull
    LotResponse updateExpiration(@NonNull UUID lotId, @NonNull LotExpirationUpdateRequest request);
}
