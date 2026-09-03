package com.positivity.domainevents.location;

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
 * publisher predates this field".
 *
 * @param bayId bay identifier (also the envelope aggregateId)
 * @param locationId owning site identifier
 * @param name bay display name
 * @param bayType owner's bay type discriminator
 * @param status raw lifecycle status, {@code ACTIVE} or {@code OUT_OF_SERVICE}
 */
public record BayUpdatedV1(
        @NonNull UUID bayId,
        @Nullable UUID locationId,
        @Nullable String name,
        @Nullable String bayType,
        @Nullable String status) {

    public static final String EVENT_TYPE = "location.bay.updated";
    public static final int SCHEMA_VERSION = 1;

    public BayUpdatedV1 {
        if (bayId == null) {
            throw new IllegalArgumentException("bayId must not be null");
        }
    }
}
