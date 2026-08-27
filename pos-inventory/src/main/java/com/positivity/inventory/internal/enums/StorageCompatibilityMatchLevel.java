package com.positivity.inventory.internal.enums;

/**
 * The catalog level a {@code storage_compatibility} row is keyed at (issue #1514).
 *
 * <p>{@link #SUBCATEGORY} rows <em>replace</em> the parent {@link #CATEGORY} rows rather than adding
 * to them: when any subcategory row exists for an item's subcategory, that set is authoritative.
 * That is what stops a battery inheriting {@code Electrical System}'s {@code SMALL_PARTS_BIN}
 * permission.
 */
public enum StorageCompatibilityMatchLevel {

    /** Keyed on a catalog category id. */
    CATEGORY,

    /** Keyed on a catalog subcategory id; overrides the parent category's rows entirely. */
    SUBCATEGORY
}
