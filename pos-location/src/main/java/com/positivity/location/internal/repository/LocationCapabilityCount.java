package com.positivity.location.internal.repository;

import java.util.UUID;

/**
 * Aggregate row returned by the repair-capability count queries: one row per
 * location that owns at least one matching child row.
 *
 * <p>Locations with no matching rows are simply absent from the result — the
 * service treats an absent location as a zero count.
 *
 * Issue: #1657
 *
 * @param locationId the location the counted rows belong to
 * @param total      number of matching rows for that location
 */
public record LocationCapabilityCount(UUID locationId, Long total) {}
