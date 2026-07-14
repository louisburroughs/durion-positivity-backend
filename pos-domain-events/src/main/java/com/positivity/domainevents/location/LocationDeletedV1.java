package com.positivity.domainevents.location;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: a location was hard-deleted by its owner (ADR-0044 §6, issue #890 Phase 4.2).
 *
 * <p>Published by pos-location on {@code location.events.v1} with
 * {@code eventType = "location.location.deleted"}. Consumers remove the location from their
 * {@code ext_location} replicas.
 *
 * @param locationId deleted location identifier (also the envelope aggregateId)
 */
public record LocationDeletedV1(@NonNull UUID locationId) {

    public static final String EVENT_TYPE = "location.location.deleted";

    public LocationDeletedV1 {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
    }
}
