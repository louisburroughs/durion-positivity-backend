package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.dto.LocationInventoryItemsResponse;
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
}
