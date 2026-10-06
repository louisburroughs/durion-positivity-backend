package com.positivity.supplier.internal.enums;

/**
 * Persistence mirror of the vendor status (#2516, SPEC §4.9): {@code ACTIVE ⇄ INACTIVE}, created
 * {@code ACTIVE}, never deleted.
 */
public enum VendorStatus {
    /** The vendor may be named on new purchase orders, bills and payments. */
    ACTIVE,
    /** Deactivated: kept for history, its profiles still run (accounting records its documents as exceptions). */
    INACTIVE
}
