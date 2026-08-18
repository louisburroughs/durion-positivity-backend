package com.positivity.inventory.service;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Handles {@code inventory.reservation.request-requested} commands (CAP #1315): the ATP-gate
 * commitment point for pos-order (checkout) and pos-workorder (part-issue).
 */
public interface ReservationRequestService {

    /**
     * Registers demand for one line and evaluates whether owned ATP at {@code locationId} covers
     * it, publishing a reservation-outcome fact either way.
     *
     * @param workorderLineId workorder line the demand is for, when demand is from a workorder
     * @param salesOrderLineId sales-order line the demand is for, when demand is from a sales order
     * @param stockItemId stock item requested
     * @param requiredQuantity quantity requested
     * @param locationId site the demand must be covered at
     */
    void handle(
            @Nullable UUID workorderLineId,
            @Nullable UUID salesOrderLineId,
            @NonNull UUID stockItemId,
            int requiredQuantity,
            @NonNull UUID locationId);
}
