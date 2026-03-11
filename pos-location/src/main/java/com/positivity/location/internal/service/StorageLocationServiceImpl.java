package com.positivity.location.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.location.internal.client.LocationInventoryInquiryClient;
import com.positivity.location.internal.dto.StorageLocationPatchRequest;
import com.positivity.location.internal.dto.StorageLocationRequest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.dto.StorageLocationValidationResponseDTO;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.StorageLocationRepository;
import com.positivity.location.service.StorageLocationInventoryTransferService;
import com.positivity.location.service.StorageLocationService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
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

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String CAPACITY = "capacity";
    private static final String TEMPERATURE = "temperature";
    private static final String UNIT_COUNT = "unitCount";
    private static final String UNIT_COUNT_SNAKE = "unit_count";
    private static final String MAX_UNIT_COUNT = "maxUnitCount";
    private static final String MAX_UNIT_COUNT_SNAKE = "max_unit_count";
    private static final String STORAGE_LOCATION_NOT_FOUND = "STORAGE_LOCATION_NOT_FOUND";
    private static final String DESTINATION_REQUIRED = "DESTINATION_REQUIRED";
    private static final String DESTINATION_NOT_FOUND = "DESTINATION_NOT_FOUND";
    private static final String DESTINATION_INACTIVE = "DESTINATION_INACTIVE";
    private static final String INVALID_DESTINATION = "INVALID_DESTINATION";

    private final StorageLocationRepository storageLocationRepository;
    private final LocationRepository locationRepository;
    private final StorageLocationInventoryTransferService storageLocationInventoryTransferService;
    private final LocationInventoryInquiryClient locationInventoryInquiryClient;

    public StorageLocationServiceImpl(StorageLocationRepository storageLocationRepository,
            LocationRepository locationRepository,
            StorageLocationInventoryTransferService storageLocationInventoryTransferService,
            LocationInventoryInquiryClient locationInventoryInquiryClient) {
        this.storageLocationRepository = storageLocationRepository;
        this.locationRepository = locationRepository;
        this.storageLocationInventoryTransferService = storageLocationInventoryTransferService;
        this.locationInventoryInquiryClient = locationInventoryInquiryClient;
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
        Location site = locationRepository.findById(siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND"));

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
        StorageLocationEntity parentEntity = null;
        if (parentId != null) {
            parentEntity = storageLocationRepository.findById(parentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "PARENT_NOT_FOUND"));
            if (!siteId.equals(parentEntity.getSiteId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PARENT_SITE_MISMATCH");
            }
        }

        StorageLocationEntity saved = storageLocationRepository.saveAndFlush(StorageLocationEntity.builder()
                .name(name)
                .barcode(barcode)
                .type(type)
                .status(StorageLocationStatus.ACTIVE)
                .site(site)
                .parentStorageLocation(parentEntity)
                .capacity(serializeJson(request.getCapacity(), CAPACITY))
                .temperature(serializeJson(request.getTemperature(), TEMPERATURE))
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
     * Retrieves existence/active validation details by storage location id.
     *
     * @param storageLocationId storage location identifier
     * @return validation details for inter-service consumers
     */
    @Override
    @NonNull
    @Transactional(readOnly = true)
    public StorageLocationValidationResponseDTO getStorageLocationValidation(@NonNull UUID storageLocationId) {
        return storageLocationRepository.findById(storageLocationId)
                .map(entity -> StorageLocationValidationResponseDTO.builder()
                        .storageLocationId(storageLocationId)
                        .siteId(entity.getSiteId())
                        .exists(true)
                        .active(entity.getStatus() == StorageLocationStatus.ACTIVE)
                        .maxUnitCapacity(extractMaxUnitCapacity(entity.getCapacity()))
                        .build())
                .orElseGet(() -> StorageLocationValidationResponseDTO.builder()
                        .storageLocationId(storageLocationId)
                        .siteId(null)
                        .exists(false)
                        .active(false)
                        .maxUnitCapacity(null)
                        .build());
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
            StorageLocationStatus status,
            @NonNull Pageable pageable) {
        Page<StorageLocationEntity> page;
        if (type != null && status != null) {
            page = storageLocationRepository.findBySiteIdAndTypeAndStatus(siteId, type, status, pageable);
        } else if (type != null) {
            page = storageLocationRepository.findBySiteIdAndType(siteId, type, pageable);
        } else if (status != null) {
            page = storageLocationRepository.findBySiteIdAndStatus(siteId, status, pageable);
        } else {
            page = storageLocationRepository.findBySiteId(siteId, pageable);
        }
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
        applyPatchedCapacity(patch, existing);
        applyPatchedTemperature(patch, existing);
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

        if (locationInventoryInquiryClient.getOnHandQuantity(storageLocationId) > 0) {
            transferInventory(siteId, existing, destinationStorageLocationId);
        }

        existing.setStatus(StorageLocationStatus.INACTIVE);
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

    private void applyPatchedCapacity(StorageLocationPatchRequest patch, StorageLocationEntity existing) {
        if (patch.getCapacity() == null) {
            return;
        }
        existing.setCapacity(serializeJson(patch.getCapacity(), CAPACITY));
    }

    private void applyPatchedTemperature(StorageLocationPatchRequest patch, StorageLocationEntity existing) {
        if (patch.getTemperature() == null) {
            return;
        }
        existing.setTemperature(serializeJson(patch.getTemperature(), TEMPERATURE));
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
        existing.setParentStorageLocation(parent);
    }

    private void applyPatchedStatus(UUID siteId, UUID storageLocationId,
            StorageLocationPatchRequest patch, StorageLocationEntity existing) {
        StorageLocationStatus requested = patch.getStatus();
        if (requested == null) {
            return;
        }
        if (requested == StorageLocationStatus.INACTIVE
                && locationInventoryInquiryClient.getOnHandQuantity(storageLocationId) > 0) {
            UUID destinationStorageLocationId = patch.getDestinationStorageLocationId();
            if (destinationStorageLocationId != null && destinationStorageLocationId.equals(storageLocationId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, INVALID_DESTINATION);
            }
            transferInventory(siteId, existing, destinationStorageLocationId);
        }
        existing.setStatus(requested);
    }

    // Issue CAP-214 #39: transfer on deactivate must be validated and atomic.
    private void transferInventory(UUID siteId, StorageLocationEntity source, UUID destinationStorageLocationId) {
        if (destinationStorageLocationId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, DESTINATION_REQUIRED);
        }
        if (destinationStorageLocationId.equals(source.getId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, INVALID_DESTINATION);
        }

        StorageLocationEntity destination = storageLocationRepository
                .findByIdAndSiteId(destinationStorageLocationId, siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, DESTINATION_NOT_FOUND));
        if (destination.getStatus() != StorageLocationStatus.ACTIVE) {
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
                .status(entity.getStatus().name())
                .siteId(entity.getSiteId())
                .parentStorageLocationId(entity.getParentStorageLocationId())
                .capacity(deserializeJson(entity.getCapacity(), CAPACITY))
                .temperature(deserializeJson(entity.getTemperature(), TEMPERATURE))
                .inventoryCount(0)
                .build();
    }

    private String serializeJson(Map<String, Object> value, String fieldName) {
        if (value == null) {
            return null;
        }
        try {
            return JSON_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be a valid JSON object", ex);
        }
    }

    private Map<String, Object> deserializeJson(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return JSON_MAPPER.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize " + fieldName + " for storage location", ex);
        }
    }

    private Integer extractMaxUnitCapacity(String capacityJson) {
        Map<String, Object> capacity = deserializeJson(capacityJson, CAPACITY);
        if (capacity == null) {
            return null;
        }
        Integer unitCapacity = toInteger(capacity.get(MAX_UNIT_COUNT));
        if (unitCapacity != null) {
            return unitCapacity;
        }
        unitCapacity = toInteger(capacity.get(MAX_UNIT_COUNT_SNAKE));
        if (unitCapacity != null) {
            return unitCapacity;
        }
        unitCapacity = toInteger(capacity.get(UNIT_COUNT));
        if (unitCapacity != null) {
            return unitCapacity;
        }
        return toInteger(capacity.get(UNIT_COUNT_SNAKE));
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
