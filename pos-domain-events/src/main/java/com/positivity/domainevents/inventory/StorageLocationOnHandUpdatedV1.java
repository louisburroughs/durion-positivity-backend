package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: the total ledger on-hand quantity at one storage location changed (ADR-0044 §6, issue
 * #899 Phase 5.3).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.storage-location-on-hand.updated"} — one snapshot per storage
 * location touched in a business transaction. Consumers (pos-location) keep an
 * {@code ext_storage_location_on_hand} replica for the "no decommission while stocked" guard.
 *
 * @param storageLocationId storage location identifier
 * @param onHandQuantity total ledger on-hand across all stock items at this location. Decimal
 *     since ADR-0055 (#1414): it is a sum over the same ledger the quantity chain widened, so an
 *     integral field here would have narrowed the one place the guard exists to protect —
 *     "is anything still stocked here" must see half a drum as stock, not as zero.
 */
public record StorageLocationOnHandUpdatedV1(
        @NonNull UUID storageLocationId, @NonNull BigDecimal onHandQuantity) {

    public static final String EVENT_TYPE = "inventory.storage-location-on-hand.updated";
    public static final int SCHEMA_VERSION = 1;

    public StorageLocationOnHandUpdatedV1 {
        if (storageLocationId == null) {
            throw new IllegalArgumentException("storageLocationId must not be null");
        }
        if (onHandQuantity == null) {
            throw new IllegalArgumentException("onHandQuantity must not be null");
        }
    }
}
