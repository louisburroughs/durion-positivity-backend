package com.positivity.location.service;

import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.LocationRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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
public class BayService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";
    private static final Set<String> ALLOWED_STATUSES = Set.of(STATUS_ACTIVE, STATUS_OUT_OF_SERVICE);

    private final BayRepository bayRepository;
    private final LocationRepository locationRepository;

    public BayService(BayRepository bayRepository, LocationRepository locationRepository) {
        this.bayRepository = bayRepository;
        this.locationRepository = locationRepository;
    }

    public BayResponse createBay(UUID locationId, BayRequest request) {
        validateLocationExists(locationId);

        String name = requireName(request.getName());
        String bayType = normalizeBayType(request.getBayType());
        String status = normalizeStatus(request.getStatus());
        int maxConcurrentVehicles = resolveMaxConcurrentVehicles(request);
        if (maxConcurrentVehicles < 1) {
            throw new IllegalArgumentException("capacity.maxConcurrentVehicles must be >= 1");
        }

        if (bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, name)
                || bayRepository.findByLocationIdAndNormalizedName(locationId, normalizeName(name)).isPresent()) {
            throw new DuplicateResourceException("Bay name already exists for location");
        }

        BayEntity entity = BayEntity.builder()
                .locationId(locationId)
                .name(name)
                .normalizedName(normalizeName(name))
                .bayType(bayType)
                .status(status)
                .maxConcurrentVehicles(maxConcurrentVehicles)
                .serviceCapabilityIds(
                        request.getServiceCapabilityIds() == null ? List.of() : request.getServiceCapabilityIds())
                .skillRequirementIds(
                        request.getSkillRequirementIds() == null ? List.of() : request.getSkillRequirementIds())
                .build();

        return toResponse(bayRepository.save(entity));
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
                throw new DuplicateResourceException("Bay name already exists for location");
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
            existing.setServiceCapabilityIds(patch.getServiceCapabilityIds());
        }
        if (patch.getSkillRequirementIds() != null) {
            existing.setSkillRequirementIds(patch.getSkillRequirementIds());
        }

        return toResponse(bayRepository.save(existing));
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
                .lastModifiedAt(entity.getLastModifiedAt())
                .build();
    }
}
