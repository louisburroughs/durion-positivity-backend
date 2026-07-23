package com.positivity.inventory.internal.enums;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Per-product tracking level replicated from pos-catalog (contract X1, #1023; odoo-parity E1,
 * issue #1038). Catalog owns the flag; the {@code ext_product} replica stores it as text and
 * this enum is the inventory-side interpretation.
 *
 * <p>Unknown or missing values map to {@link #NONE} — the replica consumer already normalizes
 * pre-v2 facts to {@code NONE}, and a value this module does not understand must never make a
 * previously untracked product start demanding lot numbers.
 */
public enum ProductTrackingLevel {
    NONE,
    LOT,
    SERIAL;

    /** Parses a replica {@code tracking_level} value; null/blank/unknown ⇒ {@link #NONE}. */
    public static @NonNull ProductTrackingLevel fromReplicaValue(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "LOT" -> LOT;
            case "SERIAL" -> SERIAL;
            default -> NONE;
        };
    }
}
