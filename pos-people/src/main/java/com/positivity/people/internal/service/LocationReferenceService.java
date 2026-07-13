package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.ExtLocationReplica;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Location existence/active checks and display names served from the {@code ext_location}
 * replica (ADR-0044 §6, #892). Replaces the retired synchronous {@code LocationReferenceClient}:
 * a location missing from the replica behaves exactly like the owner's 404 did — inactive for
 * validation, {@code 400 Unknown location} for name resolution.
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
     * Resolve a location display name from the replica.
     *
     * @param locationId location identifier
     * @return location display name, or the id string when the replica row has no name
     * @throws ResponseStatusException 400 when the location is unknown
     */
    @NonNull
    public String getLocationName(@NonNull UUID locationId) {
        ExtLocationReplica location = extLocationReplicaRepository
                .findById(locationId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown location: " + locationId));
        String name = location.getName();
        return name == null || name.isBlank() ? locationId.toString() : name;
    }
}
