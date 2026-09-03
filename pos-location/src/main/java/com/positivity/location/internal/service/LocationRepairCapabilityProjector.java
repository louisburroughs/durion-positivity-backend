package com.positivity.location.internal.service;

import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.LocationCapabilityCount;
import com.positivity.location.internal.repository.MobileUnitRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Computes the repair-capability projection for a batch of locations at query
 * time.
 *
 * <p>Deliberately not denormalized onto {@code Location}: the projection is
 * recomputed per request from two aggregate queries — one {@code GROUP BY} over
 * bays and one over mobile units, each scoped to the whole batch of location ids
 * — so it reflects a bay or mobile unit change immediately, with no cache or
 * refresh step, and without a query per location.
 *
 * Issue: #1657
 */
@Component
@RequiredArgsConstructor
public class LocationRepairCapabilityProjector {

    /**
     * Bay status counted as operational. {@code BayServiceImpl} constrains bay
     * status to exactly ACTIVE or OUT_OF_SERVICE; only ACTIVE counts.
     */
    private static final String BAY_STATUS_ACTIVE = "ACTIVE";

    /**
     * Mobile unit status counted as operational. The column is free text, so this
     * is an allow-list rather than a deny-list on INACTIVE: any future status
     * value defaults to non-operational instead of silently counting.
     */
    private static final String MOBILE_UNIT_STATUS_ACTIVE = "ACTIVE";

    private final BayRepository bayRepository;
    private final MobileUnitRepository mobileUnitRepository;

    /**
     * Projects repair capability for every supplied location.
     *
     * <p>Inactive locations are never queried and are absent from the result, so
     * {@link #capabilityFor(Map, UUID)} reports them as not repair-capable with
     * zero counts regardless of the bays or mobile units on record.
     *
     * @param locations the locations being assembled into DTOs
     * @return capability keyed by location id, for active locations only
     */
    public Map<UUID, LocationRepairCapability> project(Collection<Location> locations) {
        if (locations == null || locations.isEmpty()) {
            return Map.of();
        }

        Set<UUID> activeLocationIds = locations.stream()
                .filter(Objects::nonNull)
                .filter(Location::isActive)
                .map(Location::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (activeLocationIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> bayCounts =
                toCountsByLocationId(bayRepository.countByLocationIdInAndStatus(activeLocationIds, BAY_STATUS_ACTIVE));
        Map<UUID, Long> mobileUnitCounts = toCountsByLocationId(
                mobileUnitRepository.countByBaseLocationIdInAndStatus(activeLocationIds, MOBILE_UNIT_STATUS_ACTIVE));

        Map<UUID, LocationRepairCapability> projection = new HashMap<>();
        for (UUID locationId : activeLocationIds) {
            projection.put(
                    locationId,
                    LocationRepairCapability.of(
                            toIntCount(bayCounts.get(locationId)), toIntCount(mobileUnitCounts.get(locationId))));
        }
        return projection;
    }

    /**
     * Reads one location's capability out of a batch projection.
     *
     * @param projection result of {@link #project(Collection)}; may be empty
     * @param locationId the location to read
     * @return the projection for that location, or {@link LocationRepairCapability#NONE}
     */
    public static LocationRepairCapability capabilityFor(
            Map<UUID, LocationRepairCapability> projection, UUID locationId) {
        if (projection == null || locationId == null) {
            return LocationRepairCapability.NONE;
        }
        return projection.getOrDefault(locationId, LocationRepairCapability.NONE);
    }

    private static Map<UUID, Long> toCountsByLocationId(List<LocationCapabilityCount> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        return rows.stream()
                .filter(row -> row != null && row.locationId() != null)
                .collect(Collectors.toMap(LocationCapabilityCount::locationId, row -> {
                    Long total = row.total();
                    return total == null ? 0L : total;
                }));
    }

    private static int toIntCount(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }
}
