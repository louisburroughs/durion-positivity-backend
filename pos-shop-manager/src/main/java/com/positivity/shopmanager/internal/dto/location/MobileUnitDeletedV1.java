package com.positivity.shopmanager.internal.dto.location;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: a mobile service unit was hard-deleted by its owner (ADR-0044 §6, #1658).
 *
 * <p>Expected on {@code location.events.v1} with
 * {@code eventType = "location.mobile-unit.deleted"}; consumers remove the unit from their
 * {@code ext_mobile_unit} replicas.
 *
 * @param mobileUnitId deleted mobile-unit identifier (also the envelope aggregateId)
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
