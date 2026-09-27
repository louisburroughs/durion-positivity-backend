package com.positivity.domainevents.location;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: a tenant's whole bay-type specialty map (CAP-325 D14.1, DECISION-LOCATION-025).
 *
 * <p>Published by pos-location on {@code location.events.v1} with
 * {@code eventType = "location.bay-specialty-map.updated"}, keyed by {@code tenantId} (also the
 * envelope {@code aggregateId} and Kafka record key) — the aggregate here is the tenant's whole
 * map, not any one bay.
 *
 * <p>The map decides whether a catalog {@code operationCode} is specialty <em>at all</em>; a bay's
 * own {@code serviceCapabilityCodes} (carried on {@link BayUpdatedV1}) say only what that
 * particular bay claims. Deriving "is this operation specialty" from which bays happen to be
 * active at a location silently turns missing equipment into general work — that gap is spec
 * D14.3's amendment and this fact is how it closes: a consumer needs both facts, and neither
 * substitutes for the other.
 *
 * <p>Carries the <strong>full</strong> map, one {@link Entry} per {@code BayType} (including a
 * type with no specialty rows, whose {@code operationCodes} is then empty), never a delta: a
 * consumer rebuilds its whole replica from one message, so a fact that omitted an unclaimed type
 * would leave a stale row a consumer could not tell from "still true".
 *
 * <p>Published whenever the map changes for a tenant — today that is only tenant provisioning,
 * which seeds a new tenant from the platform template the first time {@code tenant.created} is
 * seen for it (see pos-location's {@code TenantEventsListener}) — and once per active tenant at
 * startup (an {@code ApplicationRunner} over {@code TenantIterator}) so a replica that missed live
 * traffic, or is standing up for the first time, still converges.
 *
 * <p>{@code aggregateVersion} mirrors the envelope's own field of the same name (ADR-0044 §3),
 * carried here too so a consumer that stores only the deserialized payload still has it. It
 * strictly advances on every actual change to the tenant's map; a startup republish is not itself a
 * change and carries whatever version is already on record rather than bumping it. Equal versions
 * are therefore never stale — the same rule as every other strictly-advancing fact
 * ({@link com.positivity.domainevents.ReplicaVersionGuard}) — which is what lets the startup sweep
 * repair a replica that already holds the right version but wrong rows.
 *
 * <p>Wash and detail services are never in this map (DECISION-LOCATION-025 rule 5): they are
 * ordinary catalog line items, not specialty operations, so {@code WASH_DETAIL}'s
 * {@code operationCodes} is always empty and its {@code acceptsGeneralWork} is always
 * {@code false}.
 *
 * @param tenantId owning tenant (also the envelope aggregateId)
 * @param entries one row per {@code BayType}; a type with no specialty rows still appears, with an
 *     empty {@code operationCodes}
 * @param aggregateVersion monotonic per-tenant sequence for this fact; mirrors the envelope field
 *     of the same name
 */
public record BaySpecialtyMapUpdatedV1(
        @NonNull UUID tenantId, @NonNull List<Entry> entries, long aggregateVersion) {

    public static final String EVENT_TYPE = "location.bay-specialty-map.updated";
    public static final int SCHEMA_VERSION = 1;

    public BaySpecialtyMapUpdatedV1 {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must be >= 0 but was: " + aggregateVersion);
        }
        entries = List.copyOf(entries);
    }

    /**
     * One {@code BayType}'s specialty claim (CAP-325 D14.1): the catalog operation codes only this
     * type may perform, and whether the type takes general work by default.
     *
     * @param bayType a {@code BayType} name, e.g. {@code ALIGNMENT}
     * @param operationCodes catalog operation codes this type is the only one able to perform;
     *     empty for a type that declares nothing (D14 rule 3), which includes every wash/detail
     *     service (DECISION-LOCATION-025 rule 5)
     * @param acceptsGeneralWork whether this bay type takes general work by default
     *     (DECISION-LOCATION-025); false only for {@code WASH_DETAIL}
     */
    public record Entry(@NonNull String bayType, @NonNull List<String> operationCodes, boolean acceptsGeneralWork) {

        public Entry {
            if (bayType == null || bayType.isBlank()) {
                throw new IllegalArgumentException("bayType must not be blank");
            }
            operationCodes = operationCodes == null ? List.of() : List.copyOf(operationCodes);
        }
    }
}
