package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.ExtLocationReplica;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Location existence/active checks and display names served from the {@code ext_location}
 * replica (ADR-0044 §6, #892). Replaces the retired synchronous {@code LocationReferenceClient}.
 * The replica fills by event, so a location it does not hold yet is not proof the location does
 * not exist (#1994): {@link #isLocationReplicated} lets a caller tell "not here yet" from
 * "present but inactive", and the display-name lookups degrade instead of failing.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationReferenceService {

    private final ExtLocationReplicaRepository extLocationReplicaRepository;

    /** True when the location exists in the replica and is active. */
    public boolean isLocationActive(@NonNull UUID locationId) {
        return extLocationReplicaRepository
                .findById(locationId)
                .map(ExtLocationReplica::isActive)
                .orElse(false);
    }

    /**
     * True when the replica holds a row for the location, active or not. Lets a caller that just
     * saw {@link #isLocationActive} answer {@code false} tell a location that has not replicated
     * yet (503 {@code LOCATION_REPLICATION_PENDING}) from one that is present but inactive (404).
     */
    public boolean isLocationReplicated(@NonNull UUID locationId) {
        return extLocationReplicaRepository.existsById(locationId);
    }

    /**
     * Non-throwing display-name lookup for response enrichment (issue #1680). Unlike {@link
     * #getLocationName(UUID)}, a location missing from the replica (e.g. the event-fed replica
     * has not caught up yet) or a blank name yields {@link Optional#empty()} instead of a 400, so
     * a lagging replica never turns a working endpoint into an error.
     *
     * @param locationId location identifier
     * @return the location's display name, or empty when unknown/blank
     */
    @NonNull
    public Optional<String> findLocationName(@NonNull UUID locationId) {
        return extLocationReplicaRepository
                .findById(locationId)
                .map(ExtLocationReplica::getName)
                .filter(name -> name != null && !name.isBlank());
    }

    /**
     * {@link #findLocationName(UUID)} batched across several locations in one query
     * (durion#2155): the employee register's location column resolves every distinct location on
     * a page window in a single {@code findAllById} instead of one call per row. A location
     * missing from the replica, or carrying a blank name, is simply absent from the map -- same
     * non-throwing degrade as the single-id lookup, never an error over a lagging replica.
     */
    @NonNull
    public Map<UUID, String> findLocationNames(@NonNull Collection<UUID> locationIds) {
        if (locationIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (ExtLocationReplica location : extLocationReplicaRepository.findAllById(locationIds)) {
            String name = location.getName();
            if (name != null && !name.isBlank()) {
                names.put(location.getLocationId(), name);
            }
        }
        return names;
    }
}
