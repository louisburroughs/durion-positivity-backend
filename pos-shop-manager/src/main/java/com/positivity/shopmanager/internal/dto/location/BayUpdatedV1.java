package com.positivity.shopmanager.internal.dto.location;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a service bay was created or changed (ADR-0044 §6, #1658).
 *
 * <p>Expected on {@code location.events.v1} with {@code eventType = "location.bay.updated"}.
 * pos-shop-manager maintains a read-only {@code ext_bay} replica from it so the shop dashboard can
 * list every bay at a location — including one holding no work — and name it.
 *
 * @param bayId bay identifier (also the envelope aggregateId)
 * @param locationId the site the bay belongs to
 * @param name display name, e.g. {@code Front Bay 1}
 * @param bayType the owner's bay-type string; carried for forward compatibility, unused today
 * @param status the owner's status string, e.g. {@code ACTIVE}
 * @param active convenience flag mirroring the owner's active state
 */
public record BayUpdatedV1(
        @NonNull UUID bayId,
        @Nullable UUID locationId,
        @Nullable String name,
        @Nullable String bayType,
        @Nullable String status,
        boolean active) {

    public static final String EVENT_TYPE = "location.bay.updated";
    public static final int SCHEMA_VERSION = 1;

    public BayUpdatedV1 {
        if (bayId == null) {
            throw new IllegalArgumentException("bayId must not be null");
        }
    }
}
