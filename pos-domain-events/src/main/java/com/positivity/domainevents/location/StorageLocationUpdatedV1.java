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
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "location.storage-location.updated";

    public StorageLocationUpdatedV1 {
        if (storageLocationId == null) {
            throw new IllegalArgumentException("storageLocationId must not be null");
        }
    }
}
