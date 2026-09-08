package com.positivity.inventory.internal.refdata.service;

import com.positivity.inventory.internal.dto.LocationDto;
import com.positivity.inventory.internal.dto.LocationZoneDto;
import com.positivity.inventory.internal.dto.StorageLocationDto;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.internal.service.LocationScopeService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryReferenceDataServiceImpl implements InventoryReferenceDataService {

    private final LocationRefRepository locationRefRepository;
    private final LocationScopeService locationScopeService;

    @Override
    public @NonNull Page<LocationDto> listLocations(@Nullable UUID siteId, @NonNull Pageable pageable) {
        // Served from the local location_ref roster synced from pos-location
        // (CAP-214 #40). siteId is not part of the roster contract yet.
        return locationRefRepository.findAll(pageable).map(InventoryReferenceDataServiceImpl::toLocationDto);
    }

    private static LocationDto toLocationDto(LocationRefEntity ref) {
        return LocationDto.builder()
                .locationId(ref.getLocationId())
                .name(ref.getName())
                .status(ref.getStatus())
                .timezone(ref.getTimezone())
                .active(ref.isActive())
                .build();
    }

    @Override
    public @NonNull Page<StorageLocationDto> listStorageLocations(
            @Nullable UUID locationId, @NonNull Pageable pageable) {
        // ADR-0061 §3 (#1872): a named location is gated. Without one the caller's reach would
        // narrow the page, but the placeholder below serves nothing to anyone yet, so the empty
        // page already satisfies the narrowing; the reach is not expanded.
        locationScopeService.narrowTo(locationId, InventoryPermissionRegistry.LOCATION_VIEW);
        // Placeholder stub until pos-location client integration is available.
        return Page.empty(pageable);
    }

    @Override
    public @NonNull Page<LocationZoneDto> listLocationZones(@Nullable UUID locationId, @NonNull Pageable pageable) {
        // ADR-0061 §3 (#1872): see listStorageLocations.
        locationScopeService.narrowTo(locationId, InventoryPermissionRegistry.LOCATION_VIEW);
        // Placeholder stub until pos-location client integration is available.
        return Page.empty(pageable);
    }
}
