package com.positivity.domainevents.location;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: a mobile service unit was hard-deleted by its owner (ADR-0044 §6, issue #1668).
 *
 * <p>Published by pos-location on {@code location.events.v1} with
 * {@code eventType = "location.mobile-unit.deleted"}. Consumers remove the unit from their
 * {@code ext_mobile_unit} replicas.
 *
 * <p>Carries the identifier only. Standing a unit down is a {@code status} change on
 * {@link MobileUnitUpdatedV1}, not a tombstone, and re-basing is never expressed as a delete:
 * consumers delete the row unconditionally here, keeping no version guard, so this fact is
 * versioned one past every fact the aggregate has published.
 *
 * @param mobileUnitId deleted mobile unit identifier (also the envelope aggregateId)
 */
public record MobileUnitDeletedV1(@NonNull UUID mobileUnitId) {

    public static final String EVENT_TYPE = "location.mobile-unit.deleted";
    public static final int SCHEMA_VERSION = 1;

    public MobileUnitDeletedV1 {
        if (mobileUnitId == null) {
            throw new IllegalArgumentException("mobileUnitId must not be null");
        }
    }
}
