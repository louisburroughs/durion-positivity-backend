package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.dto.LocationInventoryItemsResponse;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Service contract for reading on-hand inventory at a storage location.
 */
public interface LocationInventoryInquiryService {

    /**
     * Returns current on-hand quantity for the given storage location.
     *
     * @param locationId storage location identifier
     * @return inventory summary for the location
     */
    @NonNull
    LocationInventoryInquiryResponse getLocationInventory(@NonNull UUID locationId, @Nullable String sku);

    /**
     * Lists the on-hand stock items (with positive quantity) at the given
     * storage location.
     *
     * @param locationId storage location identifier
     * @return per-stock-item on-hand contents for the location
     */
    @NonNull
    LocationInventoryItemsResponse listLocationInventoryItems(@NonNull UUID locationId);

    /**
     * Point-in-time variant of {@link #getLocationInventory(UUID, String)} (odoo-parity A3,
     * issue #1029): on-hand computed by direct ledger aggregation with
     * {@code timestamp <= asOf}. Returns on-hand only — {@code availableToPromiseQuantity}
     * is {@code null} because historical allocation state is not reliably reconstructable.
     * Requires {@code inventory:ledger:view}; a future-dated {@code asOf} is rejected (422).
     */
    @NonNull
    LocationInventoryInquiryResponse getLocationInventoryAsOf(
            @NonNull UUID locationId, @Nullable String sku, @NonNull Instant asOf);

    /**
     * Point-in-time variant of {@link #listLocationInventoryItems(UUID)} (odoo-parity A3,
     * issue #1029): historical positive on-hand per stock item at the location as of the
     * given instant, by direct ledger aggregation. Requires {@code inventory:ledger:view};
     * a future-dated {@code asOf} is rejected (422).
     */
    @NonNull
    LocationInventoryItemsResponse listLocationInventoryItemsAsOf(@NonNull UUID locationId, @NonNull Instant asOf);
}
