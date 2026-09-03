package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.LocationRef;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.repository.LocationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for location roster retrieval used by sync consumers.
 *
 * Issue: CAP-214 #40
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationRosterServiceImpl implements LocationRosterService {

    private final LocationRepository locationRepository;
    private final LocationRepairCapabilityProjector repairCapabilityProjector;

    @Override
    @NonNull
    public Page<LocationRef> getRoster(
            @Nullable String status, @Nullable Instant sinceUpdatedAt, @NonNull Pageable pageable) {
        Page<Location> locations;
        if (status != null && sinceUpdatedAt != null) {
            locations = locationRepository.findByStatusAndUpdatedAtAfter(status, sinceUpdatedAt, pageable);
        } else if (status != null) {
            locations = locationRepository.findByStatus(status, pageable);
        } else if (sinceUpdatedAt != null) {
            locations = locationRepository.findByUpdatedAtAfter(sinceUpdatedAt, pageable);
        } else {
            locations = locationRepository.findAll(pageable);
        }

        // Issue #1657: one aggregate query over bays plus one over mobile units for the
        // whole page, never one per location.
        Map<UUID, LocationRepairCapability> repairCapability =
                repairCapabilityProjector.project(locations.getContent());
        return locations.map(location -> toLocationRef(location, repairCapability));
    }

    @NonNull
    public LocationRef toLocationRef(@NonNull Location location) {
        return toLocationRef(location, repairCapabilityProjector.project(List.of(location)));
    }

    @NonNull
    private LocationRef toLocationRef(
            @NonNull Location location, @NonNull Map<UUID, LocationRepairCapability> repairCapability) {
        LocationRepairCapability capability =
                LocationRepairCapabilityProjector.capabilityFor(repairCapability, location.getId());
        return LocationRef.builder()
                .id(location.getId())
                .name(location.getName())
                .code(location.getCode())
                .status(location.getStatus())
                .hrLocationId(location.getHrLocationId())
                .timezone(location.getTimezone())
                .updatedAt(location.getUpdatedAt())
                .hasRepairCapability(capability.hasRepairCapability())
                .build();
    }
}
