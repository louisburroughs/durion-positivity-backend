package com.positivity.domainevents.location;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a storage location was created or changed (ADR-0044 §6, issue #890 Phase 4.2).
 *
 * <p>Published by pos-location on {@code location.events.v1} with
 * {@code eventType = "location.storage-location.updated"}. Consumers (pos-inventory) rebuild
 * site topology/descendant queries and validation over their {@code ext_storage_location}
 * replica. Storage locations are never hard-deleted by the owner — decommissioning arrives as
 * a status change on this same event, so there is no companion deleted event.
 *
 * <p>{@code storageCategoryCode}, {@code hazardContainment} and {@code allowNewProduct} were
 * added additively within schema version 1 (ADR-0044 additive-within-version, issue #1514):
 * existing consumers that ignore them keep deserializing unchanged, and a consumer that reads
 * them must treat null as "the publisher predates the field" rather than as a declared value.
 *
 * @param storageLocationId storage location identifier (also the envelope aggregateId)
 * @param siteId owning site (location) id
 * @param name display name
 * @param barcode scan barcode
 * @param storageLocationType owner type name, e.g. {@code SHELF}, {@code BIN}
 * @param status owner status name, e.g. {@code ACTIVE}, {@code DECOMMISSIONED}
 * @param parentStorageLocationId parent node in the site's storage tree (null for roots)
 * @param capacity free-form capacity descriptor
 * @param maxUnitCapacity unit capacity the owner derives from the capacity descriptor for
 *     validation (issue #892; null when the descriptor carries no unit count)
 * @param temperature free-form temperature/climate descriptor
 * @param storageCategoryCode putaway capability of the location — what it is fit to hold, as
 *     opposed to {@code storageLocationType}, which is physical topology (issue #1514). One of
 *     {@code TIRE_RACK}, {@code OIL_STORAGE}, {@code BATTERY_RACK}, {@code SMALL_PARTS_BIN},
 *     {@code BULK_FLOOR}, {@code STAGING}, {@code QUARANTINE}, {@code GENERAL}. The owner
 *     resolves its nullable column to {@code GENERAL} before publishing, so a consumer never
 *     sees null here on a fact emitted after #1514 — only on a fact replayed from before it.
 * @param hazardContainment whether the location provides spill/hazard containment; the
 *     compatibility matrix requires it for battery and oil storage (issue #1514). Null only on
 *     pre-#1514 facts.
 * @param allowNewProduct whether the location will take stock of a product it is not already
 *     holding — {@code MIXED}, {@code SAME_PRODUCT_ONLY} or {@code EMPTY_ONLY} (issue #1514).
 *     Null only on pre-#1514 facts.
 * @param createdAt owner row creation timestamp
 * @param updatedAt owner row last-update timestamp
 */
public record StorageLocationUpdatedV1(
        @NonNull UUID storageLocationId,
        @Nullable UUID siteId,
        @Nullable String name,
        @Nullable String barcode,
        @Nullable String storageLocationType,
        @Nullable String status,
        @Nullable UUID parentStorageLocationId,
        @Nullable String capacity,
        @Nullable Integer maxUnitCapacity,
        @Nullable String temperature,
        @Nullable String storageCategoryCode,
        @Nullable Boolean hazardContainment,
        @Nullable String allowNewProduct,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "location.storage-location.updated";
    public static final int SCHEMA_VERSION = 1;

    public StorageLocationUpdatedV1 {
        if (storageLocationId == null) {
            throw new IllegalArgumentException("storageLocationId must not be null");
        }
    }
}
