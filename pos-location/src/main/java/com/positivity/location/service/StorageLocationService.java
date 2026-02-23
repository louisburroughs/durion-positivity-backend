package com.positivity.location.service;

import com.positivity.location.internal.dto.StorageLocationPatchRequest;
import com.positivity.location.internal.dto.StorageLocationRequest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public service contract for storage location topology operations.
 *
 * Issue: CAP-214 #39
 */
public interface StorageLocationService {

    /**
     * Creates a storage location for a site.
     *
     * @param siteId  site identifier from the route
     * @param request create request payload
     * @return created storage location
     */
    @NonNull
    StorageLocationResponse createStorageLocation(@NonNull UUID siteId, @NonNull StorageLocationRequest request);

    /**
     * Retrieves one storage location.
     *
     * @param siteId            site identifier from the route
     * @param storageLocationId storage location identifier
     * @return storage location response
     */
    @NonNull
    StorageLocationResponse getStorageLocation(@NonNull UUID siteId, @NonNull UUID storageLocationId);

    /**
     * Lists storage locations for a site with optional type filtering.
     *
     * @param siteId   site identifier from the route
     * @param type     optional type filter
     * @param status   optional status filter
     * @param pageable pagination specification
     * @return page of storage locations
     */
    @NonNull
    Page<StorageLocationResponse> listStorageLocations(@NonNull UUID siteId, StorageLocationType type,
            StorageLocationStatus status,
            @NonNull Pageable pageable);

    /**
     * Patches storage location fields.
     *
     * @param siteId            site identifier from the route
     * @param storageLocationId storage location identifier
     * @param patch             patch payload
     * @return updated storage location
     */
    @NonNull
    StorageLocationResponse patchStorageLocation(@NonNull UUID siteId, @NonNull UUID storageLocationId,
            @NonNull StorageLocationPatchRequest patch);
}
