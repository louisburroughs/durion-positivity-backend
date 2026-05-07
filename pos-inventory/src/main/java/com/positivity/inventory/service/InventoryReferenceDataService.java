package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.LocationDto;
import com.positivity.inventory.internal.dto.LocationZoneDto;
import com.positivity.inventory.internal.dto.StorageLocationDto;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InventoryReferenceDataService {

    @NonNull
    Page<LocationDto> listLocations(@Nullable UUID siteId, @NonNull Pageable pageable);

    @NonNull
    Page<StorageLocationDto> listStorageLocations(@Nullable UUID locationId, @NonNull Pageable pageable);

    @NonNull
    Page<LocationZoneDto> listLocationZones(@Nullable UUID locationId, @NonNull Pageable pageable);
}
