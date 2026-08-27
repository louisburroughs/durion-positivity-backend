package com.positivity.location.internal.enums;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a storage location is fit to <em>hold</em>, as opposed to {@link StorageLocationType},
 * which says where it sits in the site's physical topology (issue #1514).
 *
 * <p>The two are deliberately independent: a tire rack and a bulk pallet area are both
 * {@code FLOOR} topologically, but only one of them should receive tires. Putaway routes on this
 * capability, so a rule can send "certain items to locations that fit them" without inventing a
 * parallel type hierarchy.
 *
 * <p>{@link #GENERAL} is the permissive default and accepts every catalog category. A storage
 * location that has never declared a capability stores NULL and is resolved to {@code GENERAL}
 * on every read path — see {@link #orDefault}. {@link #BATTERY_RACK} and {@link #OIL_STORAGE}
 * are the containment-bearing capabilities. {@link #STAGING} and {@link #QUARANTINE} are putaway
 * <em>sources</em>, not destinations: no catalog category is compatible with them by rule.
 */
public enum StorageCategory {
    TIRE_RACK,
    OIL_STORAGE,
    BATTERY_RACK,
    SMALL_PARTS_BIN,
    BULK_FLOOR,
    STAGING,
    QUARANTINE,
    GENERAL;

    /**
     * Resolves an undeclared capability to the permissive default.
     *
     * <p>This is the single place the null-means-{@code GENERAL} rule lives. Every read boundary
     * (response mapping, published fact) funnels through it, so the column can stay nullable —
     * rows that predate the capability need no backfill — while no consumer ever has to know the
     * rule.
     *
     * @param value declared capability, or {@code null} when the location has none
     * @return {@code value}, or {@link #GENERAL} when it is {@code null}
     */
    public static @NonNull StorageCategory orDefault(@Nullable StorageCategory value) {
        return value == null ? GENERAL : value;
    }
}
