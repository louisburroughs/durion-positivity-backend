package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.SiteDefaultsRequest;
import com.positivity.location.internal.dto.SiteDefaultsResponse;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.StorageLocationRepository;
import com.positivity.location.service.SiteDefaultsService;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service implementation for configuring and retrieving site default storage
 * locations.
 *
 * Issue: CAP-214 #38
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SiteDefaultsServiceImpl implements SiteDefaultsService {

    private static final String SITE_NOT_FOUND = "SITE_NOT_FOUND";
    private static final String DEFAULT_LOCATION_ROLE_CONFLICT = "DEFAULT_LOCATION_ROLE_CONFLICT";
    private static final String DEFAULT_LOCATION_NOT_BELONGS_TO_SITE = "DEFAULT_LOCATION_NOT_BELONGS_TO_SITE";

    private final LocationRepository locationRepository;
    private final StorageLocationRepository storageLocationRepository;

    /**
     * Configures default staging and quarantine storage locations for a site.
     *
     * @param siteId  site identifier from the route
     * @param request request containing default location IDs
     * @return configured defaults response
     */
    @Override
    @NonNull
    public SiteDefaultsResponse configureDefaults(@NonNull UUID siteId, @NonNull SiteDefaultsRequest request) {
        // Issue CAP-214 #38: Ensure site exists before applying defaults.
        Location location = resolveLocationOrThrow(siteId);

        UUID stagingId = request.getDefaultStagingLocationId();
        UUID quarantineId = request.getDefaultQuarantineLocationId();

        if (stagingId == null || quarantineId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DEFAULT_LOCATION_REQUIRED");
        }

        if (Objects.equals(stagingId, quarantineId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, DEFAULT_LOCATION_ROLE_CONFLICT);
        }

        StorageLocationEntity stagingLocation = resolveStorageLocationInSite(stagingId, siteId);
        StorageLocationEntity quarantineLocation = resolveStorageLocationInSite(quarantineId, siteId);

        location.setDefaultStagingLocation(stagingLocation);
        location.setDefaultQuarantineLocation(quarantineLocation);
        locationRepository.save(location);

        return SiteDefaultsResponse.builder()
                .siteId(siteId)
                .defaultStagingLocationId(stagingId)
                .defaultQuarantineLocationId(quarantineId)
                .build();
    }

    /**
     * Retrieves currently configured defaults for a site.
     *
     * @param siteId site identifier from the route
     * @return site defaults response
     */
    @Override
    @NonNull
    @Transactional(readOnly = true)
    public SiteDefaultsResponse getDefaults(@NonNull UUID siteId) {
        Location location = resolveLocationOrThrow(siteId);

        return SiteDefaultsResponse.builder()
                .siteId(siteId)
                .defaultStagingLocationId(location.getDefaultStagingLocationId())
                .defaultQuarantineLocationId(location.getDefaultQuarantineLocationId())
                .build();
    }

    private Location resolveLocationOrThrow(UUID siteId) {
        return locationRepository
                .findById(siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, SITE_NOT_FOUND));
    }

    private StorageLocationEntity resolveStorageLocationInSite(UUID storageLocationId, UUID siteId) {
        return storageLocationRepository
                .findByIdAndSiteId(storageLocationId, siteId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, DEFAULT_LOCATION_NOT_BELONGS_TO_SITE));
    }
}
