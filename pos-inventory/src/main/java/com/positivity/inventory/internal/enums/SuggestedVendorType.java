package com.positivity.inventory.internal.enums;

/**
 * Which normalized vendor feed a purchase suggestion's vendor came from (odoo-parity F4,
 * issue #1044). A "vendor" is either a manufacturer ({@code normalized_availability},
 * keyed by {@code manufacturerId}) or a distributor
 * ({@code distributor_normalized_inventory}, keyed by {@code distributorId}); the type
 * records which source the {@code vendorRefId} identifies.
 */
public enum SuggestedVendorType {
    MANUFACTURER,
    DISTRIBUTOR
}
