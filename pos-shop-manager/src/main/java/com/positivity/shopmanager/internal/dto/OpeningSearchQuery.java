package com.positivity.shopmanager.internal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One {@code GET /v1/schedules/openings} question (#2022): "when is the next window that fits this
 * job, in one eligible bay, with a technician, at this location". Bounds are validated by the
 * service, not here — a malformed value is 400, an exceeded policy bound is 422 (AC11).
 *
 * @param locationId the facility searched
 * @param serviceIds the catalog services the job consists of (1..{@code MAX_SERVICES})
 * @param durationMinutes the job's duration; the whole of it must be free, unbroken, in one bay
 * @param earliestStart no opening starts before this instant
 * @param vehicleId optional; resolves the vehicle's GVWR class for bay duty-class and skill
 *     requirement resolution. Absent, only ANY-class requirements apply
 * @param technicianId optional; restricts openings to ones this technician can take
 * @param horizonDays how many facility-local days forward to search, from earliestStart's date
 * @param limit the most openings to return
 */
public record OpeningSearchQuery(
        @NonNull UUID locationId,
        @NonNull List<UUID> serviceIds,
        int durationMinutes,
        @NonNull Instant earliestStart,
        @Nullable UUID vehicleId,
        @Nullable UUID technicianId,
        int horizonDays,
        int limit) {}
