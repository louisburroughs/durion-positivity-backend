package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.LocationDto;
import com.positivity.inventory.internal.dto.LocationZoneDto;
import com.positivity.inventory.internal.dto.StorageLocationDto;
import com.positivity.inventory.service.InventoryReferenceDataService;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class InventoryReferenceDataServiceImpl implements InventoryReferenceDataService {

    @Override
    public @NonNull Page<LocationDto> listLocations(@Nullable UUID siteId, @NonNull Pageable pageable) {
        // Placeholder stub until pos-location client integration is available.
        return Page.empty(pageable);
    }

    @Override
    public @NonNull Page<StorageLocationDto> listStorageLocations(
            @Nullable UUID locationId, @NonNull Pageable pageable) {
        // Placeholder stub until pos-location client integration is available.
        return Page.empty(pageable);
    }

    @Override
    public @NonNull Page<LocationZoneDto> listLocationZones(@Nullable UUID locationId, @NonNull Pageable pageable) {
        // Placeholder stub until pos-location client integration is available.
        return Page.empty(pageable);
    }
}
