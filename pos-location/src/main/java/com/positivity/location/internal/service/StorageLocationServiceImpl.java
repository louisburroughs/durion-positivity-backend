package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.StorageLocationPatchRequest;
import com.positivity.location.internal.dto.StorageLocationRequest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.StorageLocationRepository;
import com.positivity.location.service.StorageLocationService;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service implementation for storage location topology behavior.
 *
 * Issue: CAP-214 #39
 */
@Service
@Transactional
public class StorageLocationServiceImpl implements StorageLocationService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String STORAGE_LOCATION_NOT_FOUND = "STORAGE_LOCATION_NOT_FOUND";
    private static final String DESTINATION_REQUIRED = "DESTINATION_REQUIRED";
    private static final String DESTINATION_NOT_FOUND = "DESTINATION_NOT_FOUND";
    private static final String DESTINATION_INACTIVE = "DESTINATION_INACTIVE";
    private static final String INVALID_DESTINATION = "INVALID_DESTINATION";

    private final StorageLocationRepository storageLocationRepository;
    private final LocationRepository locationRepository;
    private final StorageLocationInventoryTransferService storageLocationInventoryTransferService;

    public StorageLocationServiceImpl(StorageLocationRepository storageLocationRepository,
            LocationRepository locationRepository,
            @Nullable StorageLocationInventoryTransferService storageLocationInventoryTransferService) {
        this.storageLocationRepository = storageLocationRepository;
        this.locationRepository = locationRepository;
        this.storageLocationInventoryTransferService = storageLocationInventoryTransferService == null
                ? new AtomicStorageLocationInventoryTransferService(storageLocationRepository)
                : storageLocationInventoryTransferService;
    }

    /**
     * Creates a storage location after duplicate and parent validation.
     *
     * @param siteId  site identifier from the route
     * @param request create request payload
     * @return created storage location response
     */
    @Override
    @NonNull
    public StorageLocationResponse createStorageLocation(@NonNull UUID siteId,
            @NonNull StorageLocationRequest request) {
        // Issue CAP-214 #39: Validate site and uniqueness constraints for create.
        if (!locationRepository.existsById(siteId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND");
        }

        String name = normalizeRequired(request.getName(), "name");
        String barcode = normalizeOptional(request.getBarcode());
        StorageLocationType type = requireType(request.getType());

        if (storageLocationRepository.existsByNameIgnoreCaseAndSiteId(name, siteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DUPLICATE_NAME");
        }
        if (barcode != null && storageLocationRepository.existsByBarcodeAndSiteId(barcode, siteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DUPLICATE_BARCODE");
        }

        UUID parentId = request.getParentStorageLocationId();
        if (parentId != null) {
            StorageLocationEntity parent = storageLocationRepository.findById(parentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "PARENT_NOT_FOUND"));
            if (!siteId.equals(parent.getSiteId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PARENT_SITE_MISMATCH");
            }
        }

        StorageLocationEntity saved = storageLocationRepository.saveAndFlush(StorageLocationEntity.builder()
                .name(name)
                .barcode(barcode)
                .type(type)
                .status(STATUS_ACTIVE)
                .siteId(siteId)
                .parentStorageLocationId(parentId)
                .inventoryCount(0)
                .build());

        return toResponse(saved);
    }

    /**
     * Retrieves one storage location by id and site.
     *
     * @param siteId            site identifier from the route
     * @param storageLocationId storage location identifier
     * @return storage location response
     */
    @Override
    @NonNull
    @Transactional(readOnly = true)
    public StorageLocationResponse getStorageLocation(@NonNull UUID siteId, @NonNull UUID storageLocationId) {
        StorageLocationEntity entity = storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, STORAGE_LOCATION_NOT_FOUND));
        return toResponse(entity);
    }

    /**
     * Lists storage locations for a site with optional type filtering.
     *
     * @param siteId   site identifier from the route
     * @param type     optional type filter
     * @param pageable pagination specification
     * @return paged storage locations
     */
    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Page<StorageLocationResponse> listStorageLocations(@NonNull UUID siteId, StorageLocationType type,
            @NonNull Pageable pageable) {
        Page<StorageLocationEntity> page = type == null
                ? storageLocationRepository.findBySiteId(siteId, pageable)
                : storageLocationRepository.findBySiteIdAndType(siteId, type, pageable);
        return page.map(this::toResponse);
    }

    /**
     * Applies a partial update to a storage location.
     *
     * @param siteId            site identifier from the route
     * @param storageLocationId storage location identifier
     * @param patch             patch payload
     * @return updated storage location
     */
    @Override
    @NonNull
    public StorageLocationResponse patchStorageLocation(@NonNull UUID siteId, @NonNull UUID storageLocationId,
            @NonNull StorageLocationPatchRequest patch) {
        StorageLocationEntity existing = storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, STORAGE_LOCATION_NOT_FOUND));

        applyPatchedName(siteId, storageLocationId, patch, existing);
        applyPatchedBarcode(siteId, storageLocationId, patch, existing);
        applyPatchedParent(siteId, storageLocationId, patch, existing);
        applyPatchedStatus(siteId, storageLocationId, patch, existing);

        StorageLocationEntity saved = storageLocationRepository.saveAndFlush(existing);
        return toResponse(saved);
    }

    /**
     * Explicit deactivation helper used by service-level tests.
     *
     * @param siteId                       site identifier from the route
     * @param storageLocationId            storage location identifier
     * @param destinationStorageLocationId destination location for reassignment
     * @return updated storage location
     */
    @NonNull
    public StorageLocationResponse deactivateStorageLocation(@NonNull UUID siteId,
            @NonNull UUID storageLocationId,
            UUID destinationStorageLocationId) {
        StorageLocationEntity existing = storageLocationRepository.findByIdAndSiteId(storageLocationId, siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, STORAGE_LOCATION_NOT_FOUND));

        if (existing.getInventoryCount() > 0) {
            transferInventory(siteId, existing, destinationStorageLocationId);
        }

        existing.setStatus(STATUS_INACTIVE);
        StorageLocationEntity saved = storageLocationRepository.saveAndFlush(existing);
        return toResponse(saved);
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private StorageLocationType requireType(StorageLocationType type) {
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        return type;
    }

    private boolean wouldCreateCycle(UUID storageLocationId, UUID proposedParentId) {
        UUID cursor = proposedParentId;
        Set<UUID> visited = new HashSet<>();
        while (cursor != null) {
            if (!visited.add(cursor)) {
                return true;
            }
            if (cursor.equals(storageLocationId)) {
                return true;
            }
            cursor = storageLocationRepository.findById(cursor)
                    .map(StorageLocationEntity::getParentStorageLocationId)
                    .orElse(null);
        }
        return false;
    }

    private void applyPatchedName(UUID siteId, UUID storageLocationId,
            StorageLocationPatchRequest patch, StorageLocationEntity existing) {
        if (patch.getName() == null) {
            return;
        }
        String newName = normalizeRequired(patch.getName(), "name");
        if (!newName.equalsIgnoreCase(existing.getName())
                && storageLocationRepository.existsByNameIgnoreCaseAndSiteIdAndIdNot(newName, siteId,
                        storageLocationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DUPLICATE_NAME");
        }
        existing.setName(newName);
    }

    private void applyPatchedBarcode(UUID siteId, UUID storageLocationId,
            StorageLocationPatchRequest patch, StorageLocationEntity existing) {
        if (patch.getBarcode() == null) {
            return;
        }
        String newBarcode = normalizeOptional(patch.getBarcode());
        if (newBarcode != null
                && !newBarcode.equals(existing.getBarcode())
                && storageLocationRepository.existsByBarcodeAndSiteIdAndIdNot(newBarcode, siteId,
                        storageLocationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DUPLICATE_BARCODE");
        }
        existing.setBarcode(newBarcode);
    }

    private void applyPatchedParent(UUID siteId, UUID storageLocationId,
            StorageLocationPatchRequest patch, StorageLocationEntity existing) {
        UUID proposedParentId = patch.getParentStorageLocationId();
        if (proposedParentId == null) {
            return;
        }
        if (storageLocationId.equals(proposedParentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CYCLE_DETECTED");
        }

        StorageLocationEntity parent = storageLocationRepository.findById(proposedParentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "PARENT_NOT_FOUND"));
        if (!siteId.equals(parent.getSiteId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PARENT_SITE_MISMATCH");
        }

        if (storageLocationRepository.existsCycleForParent(storageLocationId, proposedParentId)
                || wouldCreateCycle(storageLocationId, proposedParentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CYCLE_DETECTED");
        }
        existing.setParentStorageLocationId(proposedParentId);
    }

    private void applyPatchedStatus(UUID siteId, UUID storageLocationId,
            StorageLocationPatchRequest patch, StorageLocationEntity existing) {
        String requestedStatus = normalizeOptional(patch.getStatus());
        if (requestedStatus == null) {
            return;
        }
        String normalizedStatus = requestedStatus.toUpperCase(Locale.ROOT);
        if (STATUS_INACTIVE.equals(normalizedStatus) && existing.getInventoryCount() > 0) {
            UUID destinationStorageLocationId = patch.getDestinationStorageLocationId();
            if (destinationStorageLocationId != null && destinationStorageLocationId.equals(storageLocationId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, INVALID_DESTINATION);
            }
            transferInventory(siteId, existing, destinationStorageLocationId);
        }
        existing.setStatus(normalizedStatus);
    }

    // Issue CAP-214 #39: transfer on deactivate must be validated and atomic.
    private void transferInventory(UUID siteId, StorageLocationEntity source, UUID destinationStorageLocationId) {
        if (destinationStorageLocationId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, DESTINATION_REQUIRED);
        }
        if (destinationStorageLocationId.equals(source.getId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, INVALID_DESTINATION);
        }

        StorageLocationEntity destination = storageLocationRepository.findByIdAndSiteId(destinationStorageLocationId, siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, DESTINATION_NOT_FOUND));
        if (!STATUS_ACTIVE.equalsIgnoreCase(destination.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, DESTINATION_INACTIVE);
        }

        storageLocationInventoryTransferService.transferAll(source, destination);
    }

    private StorageLocationResponse toResponse(StorageLocationEntity entity) {
        return StorageLocationResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .barcode(entity.getBarcode())
                .type(entity.getType())
                .status(entity.getStatus())
                .siteId(entity.getSiteId())
                .parentStorageLocationId(entity.getParentStorageLocationId())
                .inventoryCount(entity.getInventoryCount())
                .build();
    }
}
