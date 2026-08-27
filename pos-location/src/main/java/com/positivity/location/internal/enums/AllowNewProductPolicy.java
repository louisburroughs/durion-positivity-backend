package com.positivity.location.internal.enums;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Whether a storage location will take stock of a product it is not already holding
 * (issue #1514). Consumed by the putaway compatibility check in pos-inventory alongside
 * {@link StorageCategory}.
 *
 * <p>{@link #MIXED} is the default and the only value that lets a destination accumulate
 * unrelated products, which is what an ordinary shelf bin does. The two restrictive values exist
 * for locations where mixing is an operational error rather than untidiness — a dedicated rack
 * that must stay single-SKU for counting, or a location that may only be filled from empty.
 */
public enum AllowNewProductPolicy {

    /** Accepts any product regardless of what the location already holds. */
    MIXED,

    /** Accepts only the product the location already holds (or any product when it is empty). */
    SAME_PRODUCT_ONLY,

    /** Accepts stock only while the location holds nothing at all. */
    EMPTY_ONLY;

    /**
     * Resolves an unset policy to {@link #MIXED}.
     *
     * <p>The column is non-null with a database default, so this only guards the in-memory edge
     * where an entity was built through a setter without a policy.
     *
     * @param value declared policy, or {@code null} when unset
     * @return {@code value}, or {@link #MIXED} when it is {@code null}
     */
    public static @NonNull AllowNewProductPolicy orDefault(@Nullable AllowNewProductPolicy value) {
        return value == null ? MIXED : value;
    }
}
