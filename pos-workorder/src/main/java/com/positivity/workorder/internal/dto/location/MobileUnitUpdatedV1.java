package com.positivity.workorder.internal.dto.location;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a mobile service unit was created or changed (ADR-0044 §6, #1656).
 *
 * <p>Expected on {@code location.events.v1} with
 * {@code eventType = "location.mobile-unit.updated"}. pos-workorder maintains a read-only
 * {@code ext_mobile_unit} replica from it so the dispatch board can resolve unit identity for a
 * mobile assignment and list idle units.
 *
 * @param mobileUnitId mobile-unit identifier (also the envelope aggregateId)
 * @param baseLocationId the site the unit is based at and dispatched from
 * @param name display name, e.g. {@code Van 3}
 * @param status the owner's status string, e.g. {@code ACTIVE}
 * @param active convenience flag mirroring the owner's active state
 */
public record MobileUnitUpdatedV1(
        @NonNull UUID mobileUnitId,
        @Nullable UUID baseLocationId,
        @Nullable String name,
        @Nullable String status,
        boolean active) {

    public static final String EVENT_TYPE = "location.mobile-unit.updated";
    public static final int SCHEMA_VERSION = 1;

    public MobileUnitUpdatedV1 {
        if (mobileUnitId == null) {
            throw new IllegalArgumentException("mobileUnitId must not be null");
        }
    }
}
