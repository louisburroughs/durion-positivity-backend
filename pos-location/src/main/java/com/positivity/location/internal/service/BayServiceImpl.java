package com.positivity.location.internal.service;

import com.positivity.location.service.BayService;

import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.ServiceLocationCapabilityEntity;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.ServiceLocationCapabilityRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API service for bay operations.
 *
 * Issue: CAP-136 #77
 */
@Service
@Transactional
public class BayServiceImpl implements BayService {

    private static final String BAY_NAME_TAKEN = "BAY_NAME_TAKEN";
    private static final String BAY_CONFLICT = "BAY_CONFLICT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";
    private static final Set<String> ALLOWED_STATUSES = Set.of(STATUS_ACTIVE, STATUS_OUT_OF_SERVICE);

    private final BayRepository bayRepository;
    private final LocationRepository locationRepository;
    private final ServiceLocationCapabilityRepository serviceLocationCapabilityRepository;

    public BayServiceImpl(
            BayRepository bayRepository,
            LocationRepository locationRepository,
            ServiceLocationCapabilityRepository serviceLocationCapabilityRepository) {
        this.bayRepository = bayRepository;
        this.locationRepository = locationRepository;
        this.serviceLocationCapabilityRepository = serviceLocationCapabilityRepository;
    }

    public BayResponse createBay(UUID locationId, BayRequest request) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Location not found"));

        String name = requireName(request.getName());
        String bayType = normalizeBayType(request.getBayType());
        String status = normalizeStatus(request.getStatus());
        int maxConcurrentVehicles = resolveMaxConcurrentVehicles(request);
        if (maxConcurrentVehicles < 1) {
            throw new IllegalArgumentException("capacity.maxConcurrentVehicles must be >= 1");
        }

        if (bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, name)
                || bayRepository.findByLocationIdAndNormalizedName(locationId, normalizeName(name)).isPresent()) {
            throw new DuplicateResourceException(BAY_NAME_TAKEN);
        }

        List<String> validatedCapabilityCodes = validateServiceCapabilityIds(request.getServiceCapabilityIds());

        BayEntity entity = BayEntity.builder()
                .location(location)
                .name(name)
                .normalizedName(normalizeName(name))
                .bayType(bayType)
                .status(status)
                .maxConcurrentVehicles(maxConcurrentVehicles)
                .serviceCapabilityIds(validatedCapabilityCodes)
                .skillRequirementIds(
                        request.getSkillRequirementIds() == null ? List.of() : request.getSkillRequirementIds())
                .build();

        try {
            return toResponse(bayRepository.save(entity));
        } catch (DataIntegrityViolationException exception) {
            throw toBayConflictException(exception);
        }
    }

    @Transactional(readOnly = true)
    public Page<BayResponse> listBays(UUID locationId, String status, String bayType, Pageable pageable) {
        validateLocationExists(locationId);

        String normalizedStatus = status == null || status.isBlank() ? null : normalizeStatus(status);
        String normalizedBayType = bayType == null || bayType.isBlank() ? null : normalizeBayType(bayType);

        Page<BayEntity> page;
        if (normalizedStatus != null && normalizedBayType != null) {
            page = bayRepository.findByLocationIdAndStatusAndBayType(locationId, normalizedStatus, normalizedBayType,
                    pageable);
        } else if (normalizedStatus != null) {
            page = bayRepository.findByLocationIdAndStatus(locationId, normalizedStatus, pageable);
        } else if (normalizedBayType != null) {
            page = bayRepository.findByLocationIdAndBayType(locationId, normalizedBayType, pageable);
        } else {
            page = bayRepository.findByLocationId(locationId, pageable);
        }
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public BayResponse getBay(UUID locationId, UUID bayId) {
        validateLocationExists(locationId);
        BayEntity bay = bayRepository.findByIdAndLocationId(bayId, locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Bay not found"));
        return toResponse(bay);
    }

    public BayResponse patchBay(UUID locationId, UUID bayId, BayPatchRequest patch) {
        validateLocationExists(locationId);
        BayEntity existing = bayRepository.findByIdAndLocationId(bayId, locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Bay not found"));

        if (patch.getName() != null) {
            String name = requireName(patch.getName());
            if (!normalizeName(existing.getName()).equals(normalizeName(name))
                    && bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, name)) {
                throw new DuplicateResourceException(BAY_NAME_TAKEN);
            }
            existing.setName(name);
        }

        if (patch.getBayType() != null) {
            existing.setBayType(normalizeBayType(patch.getBayType()));
        }

        if (patch.getStatus() != null) {
            existing.setStatus(normalizeStatus(patch.getStatus()));
        }

        Integer maxConcurrentVehicles = null;
        if (patch.getCapacity() != null) {
            maxConcurrentVehicles = patch.getCapacity().getMaxConcurrentVehicles();
        }
        if (maxConcurrentVehicles == null) {
            maxConcurrentVehicles = patch.getMaxConcurrentVehicles();
        }
        if (maxConcurrentVehicles != null) {
            if (maxConcurrentVehicles < 1) {
                throw new IllegalArgumentException("capacity.maxConcurrentVehicles must be >= 1");
            }
            existing.setMaxConcurrentVehicles(maxConcurrentVehicles);
        }

        if (patch.getServiceCapabilityIds() != null) {
            existing.setServiceCapabilityIds(validateServiceCapabilityIds(patch.getServiceCapabilityIds()));
        }
        if (patch.getSkillRequirementIds() != null) {
            existing.setSkillRequirementIds(patch.getSkillRequirementIds());
        }

        try {
            return toResponse(bayRepository.save(existing));
        } catch (DataIntegrityViolationException exception) {
            throw toBayConflictException(exception);
        }
    }

    private void validateLocationExists(UUID locationId) {
        if (!locationRepository.existsById(locationId)) {
            throw new ResourceNotFoundException("Location not found");
        }
    }

    private String requireName(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        return value.trim();
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String value) {
        String resolved = value == null ? STATUS_ACTIVE : value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(resolved)) {
            throw new IllegalArgumentException("Invalid status: " + value);
        }
        return resolved;
    }

    private String normalizeBayType(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("bayType is required");
        }
        try {
            return BayType.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid bayType: " + value, exception);
        }
    }

    private int resolveMaxConcurrentVehicles(BayRequest request) {
        Integer capacityValue = request.getCapacity() != null ? request.getCapacity().getMaxConcurrentVehicles() : null;
        Integer resolved = capacityValue != null ? capacityValue : request.getMaxConcurrentVehicles();
        if (resolved == null) {
            throw new IllegalArgumentException("capacity.maxConcurrentVehicles is required");
        }
        return resolved;
    }

    private List<String> validateServiceCapabilityIds(List<String> serviceCapabilityIds) {
        if (serviceCapabilityIds == null || serviceCapabilityIds.isEmpty()) {
            return List.of();
        }
        if (serviceLocationCapabilityRepository == null) {
            throw new IllegalArgumentException("service capabilities are not configured");
        }

        NormalizedServiceCapabilities normalized = normalizeServiceCapabilityIds(serviceCapabilityIds);
        throwIfInvalidServiceCapabilityIds(normalized.invalidCodes());

        Set<String> requestedCodes = new LinkedHashSet<>(normalized.normalizedCodes());
        Set<String> foundCodes = findServiceCapabilityCodes(requestedCodes);
        Set<String> missingCodes = findMissingServiceCapabilityCodes(requestedCodes, foundCodes);
        throwIfInvalidServiceCapabilityIds(missingCodes);

        return normalized.normalizedCodes();
    }

    private NormalizedServiceCapabilities normalizeServiceCapabilityIds(List<String> serviceCapabilityIds) {
        List<String> normalizedCodes = new ArrayList<>();
        Set<String> invalidCodes = new LinkedHashSet<>();
        for (String serviceCapabilityId : serviceCapabilityIds) {
            String normalized = normalizeServiceCapabilityId(serviceCapabilityId);
            if (normalized.isBlank()) {
                invalidCodes.add("<blank>");
                continue;
            }
            normalizedCodes.add(normalized);
        }
        return new NormalizedServiceCapabilities(normalizedCodes, invalidCodes);
    }

    private String normalizeServiceCapabilityId(String serviceCapabilityId) {
        return serviceCapabilityId == null ? "" : serviceCapabilityId.trim().toUpperCase(Locale.ROOT);
    }

    private Set<String> findServiceCapabilityCodes(Set<String> requestedCodes) {
        Set<String> foundCodes = new LinkedHashSet<>();
        List<ServiceLocationCapabilityEntity> found = serviceLocationCapabilityRepository.findByCodeIn(requestedCodes);
        if (found == null) {
            return foundCodes;
        }
        for (ServiceLocationCapabilityEntity capability : found) {
            if (capability.getCode() == null) {
                continue;
            }
            foundCodes.add(capability.getCode().trim().toUpperCase(Locale.ROOT));
        }
        return foundCodes;
    }

    private Set<String> findMissingServiceCapabilityCodes(Set<String> requestedCodes, Set<String> foundCodes) {
        Set<String> missingCodes = new LinkedHashSet<>();
        for (String requestedCode : requestedCodes) {
            if (!foundCodes.contains(requestedCode)) {
                missingCodes.add(requestedCode);
            }
        }
        return missingCodes;
    }

    private void throwIfInvalidServiceCapabilityIds(Set<String> invalidCodes) {
        if (!invalidCodes.isEmpty()) {
            throw new IllegalArgumentException("Invalid serviceCapabilityIds: " + String.join(", ", invalidCodes));
        }
    }

    private DuplicateResourceException toBayConflictException(DataIntegrityViolationException exception) {
        if (isNameConstraintViolation(exception)) {
            return new DuplicateResourceException(BAY_NAME_TAKEN);
        }
        return new DuplicateResourceException(BAY_CONFLICT);
    }

    private boolean isNameConstraintViolation(Throwable throwable) {
        String details = lowerCaseMessages(throwable);
        return details.contains("uq_bays_location_normalized_name")
                || details.contains("location_id")
                || details.contains("normalized_name");
    }

    private String lowerCaseMessages(Throwable throwable) {
        StringBuilder all = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null) {
                all.append(message.toLowerCase(Locale.ROOT)).append(' ');
            }
            cursor = cursor.getCause();
        }
        return all.toString();
    }

    private BayResponse toResponse(BayEntity entity) {
        return BayResponse.builder()
                .id(entity.getId())
                .locationId(entity.getLocationId())
                .name(entity.getName())
                .bayType(entity.getBayType())
                .status(entity.getStatus())
                .maxConcurrentVehicles(entity.getMaxConcurrentVehicles())
                .serviceCapabilityIds(
                        entity.getServiceCapabilityIds() == null ? List.of() : entity.getServiceCapabilityIds())
                .skillRequirementIds(
                        entity.getSkillRequirementIds() == null ? List.of() : entity.getSkillRequirementIds())
                .createdAt(entity.getCreatedAt())
                .lastModifiedAt(entity.getUpdatedAt())
                .build();
    }

    private record NormalizedServiceCapabilities(List<String> normalizedCodes, Set<String> invalidCodes) {
    }
}
