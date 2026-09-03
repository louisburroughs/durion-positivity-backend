package com.positivity.domainevents.location;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Fact: a service bay was hard-deleted by its owner (ADR-0044 §6, issue #1668).
 *
 * <p>Published by pos-location on {@code location.events.v1} with
 * {@code eventType = "location.bay.deleted"}. Consumers remove the bay from their {@code ext_bay}
 * replicas.
 *
 * <p>Carries the identifier only. Taking a bay out of service is a {@code status} change on
 * {@link BayUpdatedV1}, not a tombstone: consumers delete the row unconditionally here, keeping no
 * version guard, so this fact is versioned one past every fact the aggregate has published.
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
