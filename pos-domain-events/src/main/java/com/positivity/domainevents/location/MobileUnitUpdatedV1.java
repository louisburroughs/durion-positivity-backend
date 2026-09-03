package com.positivity.domainevents.location;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a mobile service unit was created or changed by its owner (ADR-0044 §6, issue #1668).
 *
 * <p>Published by pos-location on {@code location.events.v1} with
 * {@code eventType = "location.mobile-unit.updated"}. Consumers keep an {@code ext_mobile_unit}
 * replica keyed by {@code mobileUnitId} and scope their unit rosters by {@code baseLocationId}.
 *
 * <p>The site scope is named {@code baseLocationId} here and {@code locationId} on
 * {@link BayUpdatedV1}; the asymmetry mirrors the owner's own columns
 * ({@code mobile_units.base_location_id} vs {@code bays.location_id}) and is deliberate. It is
 * notably <em>not</em> {@code siteId}, the name {@link StorageLocationUpdatedV1} uses — consumers
 * reject that shape rather than silently replicate a row with no site.
 *
 * <p>{@code status} is the owner's raw lifecycle string, written only as {@code ACTIVE} or
 * {@code INACTIVE}, never a derived boolean — consumers derive activeness with an allow-list on
 * {@code ACTIVE}.
 *
 * <p>Re-basing a unit to another site travels on this fact: {@code baseLocationId} is carried on
 * every emission and consumers rebuild the row from the payload, so the unit leaves the old site's
 * roster and joins the new one on the next read. A re-base is never expressed as
 * {@link MobileUnitDeletedV1} followed by an update — the tombstone path is an unguarded delete,
 * and an out-of-order pair would resurrect or drop the row.
 *
 * <p>New fields may be added additively within schema version 1; a consumer reads null as "the
 * publisher predates this field".
 *
 * @param mobileUnitId mobile unit identifier (also the envelope aggregateId)
 * @param baseLocationId owning base site identifier
 * @param name unit display name
 * @param status raw lifecycle status, {@code ACTIVE} or {@code INACTIVE}
 */
public record MobileUnitUpdatedV1(
        @NonNull UUID mobileUnitId,
        @Nullable UUID baseLocationId,
        @Nullable String name,
        @Nullable String status) {

    public static final String EVENT_TYPE = "location.mobile-unit.updated";
    public static final int SCHEMA_VERSION = 1;

    public MobileUnitUpdatedV1 {
        if (mobileUnitId == null) {
            throw new IllegalArgumentException("mobileUnitId must not be null");
        }
    }
}
