package com.positivity.domainevents.location;

import java.time.Instant;
import java.util.List;
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
 * <p>New fields are added additively; a consumer reads null as "the publisher predates this
 * field". v2 adds {@code serviceCapabilityCodes} (CAP-325 D14). v3 adds {@code outOfServiceReason},
 * {@code outOfServiceNote} and {@code expectedReturnAt} (DECISION-LOCATION-026, issue #2264):
 * {@code status} now also carries {@code RETIRED}, which no longer has its own tombstone fact — see
 * {@link MobileUnitDeletedV1}, which pos-location no longer emits. The three new fields are null
 * except when {@code status} is {@code OUT_OF_SERVICE}, and all three clear (become null) the
 * moment the unit returns to {@code ACTIVE}.
 *
 * @param mobileUnitId mobile unit identifier (also the envelope aggregateId)
 * @param baseLocationId owning base site identifier
 * @param name unit display name
 * @param status raw lifecycle status, {@code ACTIVE}, {@code OUT_OF_SERVICE} or {@code RETIRED}
 * @param serviceCapabilityCodes catalog operation codes the unit can perform off-site (v2); null
 *     from a v1 publisher
 * @param outOfServiceReason why the unit is {@code OUT_OF_SERVICE} (v3); null otherwise or on a
 *     pre-v3 emission
 * @param outOfServiceNote free-text detail for {@code outOfServiceReason} (v3); null when not given
 * @param expectedReturnAt advisory expected return-to-service time (v3); never used by scheduling
 */
public record MobileUnitUpdatedV1(
        @NonNull UUID mobileUnitId,
        @Nullable UUID baseLocationId,
        @Nullable String name,
        @Nullable String status,
        @Nullable List<String> serviceCapabilityCodes,
        @Nullable String outOfServiceReason,
        @Nullable String outOfServiceNote,
        @Nullable Instant expectedReturnAt) {
    public static final String EVENT_TYPE = "location.mobile-unit.updated";

    /**
     * v3 (DECISION-LOCATION-026, additive per ADR-0044 §3): {@code outOfServiceReason}, {@code
     * outOfServiceNote} and {@code expectedReturnAt}.
     */
    public static final int SCHEMA_VERSION = 3;

    /** The v1 shape, for a caller that carries no capability list. */
    public MobileUnitUpdatedV1(
            @NonNull UUID mobileUnitId, @Nullable UUID baseLocationId, @Nullable String name, @Nullable String status) {
        this(mobileUnitId, baseLocationId, name, status, null, null, null, null);
    }

    /** The v2 shape, for a caller that carries no out-of-service fields. */
    public MobileUnitUpdatedV1(
            @NonNull UUID mobileUnitId,
            @Nullable UUID baseLocationId,
            @Nullable String name,
            @Nullable String status,
            @Nullable List<String> serviceCapabilityCodes) {
        this(mobileUnitId, baseLocationId, name, status, serviceCapabilityCodes, null, null, null);
    }

    public MobileUnitUpdatedV1 {
        if (mobileUnitId == null) {
            throw new IllegalArgumentException("mobileUnitId must not be null");
        }
    }
}
