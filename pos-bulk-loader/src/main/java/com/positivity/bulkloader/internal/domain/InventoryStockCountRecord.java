package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/**
 * An opening-stock line: how much of one product sits in one place.
 *
 * <p>The destination is named the way an operator would name it — the site's code and the storage
 * location's name — and resolved to {@code locationId} as the file loads. A file that already knows
 * the storage location's id may supply it and leave the two name columns blank.
 */
@Data
public class InventoryStockCountRecord {

    private String sku;
    private String quantity;
    private String unitOfMeasure;
    private String reasonCode;

    private String locationCode;
    private String storageLocationName;

    /** Resolved from {@code locationCode} + {@code storageLocationName}, or supplied directly. */
    private String locationId;
}
