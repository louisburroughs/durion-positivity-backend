package com.positivity.domainevents.location;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a service bay was created or changed by its owner (ADR-0044 §6, issue #1668).
 *
 * <p>Published by pos-location on {@code location.events.v1} with
 * {@code eventType = "location.bay.updated"}. Consumers keep an {@code ext_bay} replica keyed by
 * {@code bayId} and scope their bay rosters by {@code locationId}.
 *
 * <p>{@code status} is the owner's raw lifecycle string ({@code ACTIVE} | {@code OUT_OF_SERVICE}),
 * never a derived boolean. {@code BayEntity} has no active column, so an invented {@code active}
 * flag would deserialize to {@code false} on every real event; consumers derive activeness
 * themselves with an allow-list on {@code ACTIVE}.
 *
 * <p>{@code locationId} is carried on every emission, not only when the owning site is what
 * changed: consumers rebuild the whole replica row from this payload, so a fact that omitted it
 * would blank the column the roster query filters on and make the bay invisible.
 *
 * <p>New fields may be added additively within schema version 1; a consumer reads null as "the
 * publisher predates this field". The three eligibility fields below were added that way under
 * CAP-325 — existing replicas fill on the next emission or on a
 * {@code POST .../facts/replay} backfill, and until then read null, which every consumer must
 * treat as "not yet published", never as "none".
 *
 * <p>{@code serviceCapabilityCodes} are catalog {@code operationCode}s (ADR-0059 §3,
 * {@code UPPER-DASH}), not registry identifiers — the name changed from {@code serviceCapabilityIds}
 * because the values were always codes and the old name lied. Under CAP-325 D14 a bay lists only
 * the operations its type is the <em>only</em> one able to perform; an empty list is a general
 * bay, and a general bay is eligible for every operation no specialty bay claims. A consumer must
 * therefore never read an empty list as "can do nothing".
 *
 * <p>{@code maxDutyClass} is a GVWR class ceiling, 1–8 (CAP-325 D13): the heaviest vehicle class
 * the bay accepts. Null means unconstrained. It is a class number, not a token, so a consumer
 * compares it numerically against the job vehicle's {@code gvwrClass}.
 *
 * <p>{@code acceptsGeneralWork} (DECISION-LOCATION-025, additive within schema version 1) is the
 * owner's {@code BayType.acceptsGeneralWork()} for this bay's type — {@code false} only for
 * {@code WASH_DETAIL}. It replaces deriving "does this type take general work" from the type name:
 * a consumer must read it, not re-implement the {@code WASH_DETAIL} exception itself. Null on a
 * pre-DECISION-LOCATION-025 emission, meaning "the publisher predates this field", never "no".
 *
 * <p>{@code status}, {@code outOfServiceReason}, {@code outOfServiceNote} and {@code
 * expectedReturnAt} (DECISION-LOCATION-026, additive within schema version 1, issue #2264):
 * {@code status} now also carries {@code RETIRED}, which no longer has its own tombstone fact — see
 * {@link BayDeletedV1}, which pos-location no longer emits. The three out-of-service fields are
 * null except when {@code status} is {@code OUT_OF_SERVICE}, and all three clear (become null) the
 * moment the bay returns to {@code ACTIVE}. {@code displayOrder} is the owner's sort key for bay
 * lists; a consumer that renders an ordered roster reads it, nulls last.
 *
 * @param bayId bay identifier (also the envelope aggregateId)
 * @param locationId owning site identifier
 * @param name bay display name
 * @param bayType owner's bay type discriminator
 * @param status raw lifecycle status, {@code ACTIVE}, {@code OUT_OF_SERVICE} or {@code RETIRED}
 * @param serviceCapabilityCodes catalog operation codes this bay type is the only one able to
 *     perform; empty for a general bay; null on a pre-CAP-325 emission
 * @param maxConcurrentVehicles how many vehicles the bay physically holds; null on a pre-CAP-325
 *     emission
 * @param maxDutyClass heaviest GVWR class (1–8) the bay accepts; null when unconstrained or on a
 *     pre-CAP-325 emission
 * @param acceptsGeneralWork whether this bay type takes general work by default; false only for
 *     {@code WASH_DETAIL}; null on a pre-DECISION-LOCATION-025 emission
 * @param outOfServiceReason why the bay is {@code OUT_OF_SERVICE}; null otherwise or on a
 *     pre-DECISION-LOCATION-026 emission
 * @param outOfServiceNote free-text detail for {@code outOfServiceReason}; null when not given
 * @param expectedReturnAt advisory expected return-to-service time; never used by scheduling
 * @param displayOrder sort key for bay lists and the dispatch board; null sorts last
 */
public record BayUpdatedV1(
        @NonNull UUID bayId,
        @Nullable UUID locationId,
        @Nullable String name,
        @Nullable String bayType,
        @Nullable String status,
        @Nullable List<String> serviceCapabilityCodes,
        @Nullable Integer maxConcurrentVehicles,
        @Nullable Integer maxDutyClass,
        @Nullable Boolean acceptsGeneralWork,
        @Nullable String outOfServiceReason,
        @Nullable String outOfServiceNote,
        @Nullable Instant expectedReturnAt,
        @Nullable Integer displayOrder) {

    public static final String EVENT_TYPE = "location.bay.updated";
    public static final int SCHEMA_VERSION = 1;

    public BayUpdatedV1 {
        if (bayId == null) {
            throw new IllegalArgumentException("bayId must not be null");
        }
        if (maxDutyClass != null && (maxDutyClass < 1 || maxDutyClass > 8)) {
            throw new IllegalArgumentException("maxDutyClass must be a GVWR class 1..8, was " + maxDutyClass);
        }
    }

    /** The pre-DECISION-LOCATION-026 shape, for a caller that carries none of the four new fields. */
    public BayUpdatedV1(
            @NonNull UUID bayId,
            @Nullable UUID locationId,
            @Nullable String name,
            @Nullable String bayType,
            @Nullable String status,
            @Nullable List<String> serviceCapabilityCodes,
            @Nullable Integer maxConcurrentVehicles,
            @Nullable Integer maxDutyClass,
            @Nullable Boolean acceptsGeneralWork) {
        this(
                bayId,
                locationId,
                name,
                bayType,
                status,
                serviceCapabilityCodes,
                maxConcurrentVehicles,
                maxDutyClass,
                acceptsGeneralWork,
                null,
                null,
                null,
                null);
    }
}
