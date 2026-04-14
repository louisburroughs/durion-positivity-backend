package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.LocationRef;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.service.LocationRosterService;
import java.time.Instant;
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

        return locations.map(this::toLocationRef);
    }

    @Override
    @NonNull
    public LocationRef toLocationRef(@NonNull Location location) {
        return LocationRef.builder()
                .id(location.getId())
                .name(location.getName())
                .code(location.getCode())
                .status(location.getStatus())
                .hrLocationId(location.getHrLocationId())
                .updatedAt(location.getUpdatedAt())
                .build();
    }
}
