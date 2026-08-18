package com.positivity.order.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * This module's read-only view of stock, answered from the pos-inventory-owned availability
 * replica rather than a synchronous call (ADR-0044 domain wall).
 *
 * <p>Availability is always scoped to a location: "is there enough" is only a meaningful question
 * about somewhere. Per CAP #1315 that is the selling location alone — stock sitting at a sibling
 * site is not available to this sale until someone transfers it, and counting it would promise
 * goods no one has committed to move.
 */
public interface InventoryPort {

    /**
     * @param itemSku stock item to check
     * @param quantity quantity the line needs
     * @param locationId selling location; a null location has no replica row and therefore no
     *     known stock, which reports insufficient rather than guessing
     */
    @NonNull
    InventoryResult checkAvailability(@NonNull String itemSku, int quantity, @Nullable UUID locationId);
}
