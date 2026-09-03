package com.positivity.workorder.internal.dto.location;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: a service bay was hard-deleted by its owner (ADR-0044 §6, #1656).
 *
 * <p>Expected on {@code location.events.v1} with {@code eventType = "location.bay.deleted"};
 * consumers remove the bay from their {@code ext_bay} replicas.
 *
 * @param bayId deleted bay identifier (also the envelope aggregateId)
 */
public record BayDeletedV1(@NonNull UUID bayId) {

    public static final String EVENT_TYPE = "location.bay.deleted";
    public static final int SCHEMA_VERSION = 1;

    public BayDeletedV1 {
        if (bayId == null) {
            throw new IllegalArgumentException("bayId must not be null");
        }
    }
}
