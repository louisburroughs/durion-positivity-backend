package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.location.internal.repository.LocationRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private final ExtCatalogServiceReplicaRepository extCatalogServiceReplicaRepository;
    private final BaySpecialtyOperationRepository baySpecialtyOperationRepository;
    private final LocationFactPublisher locationFactPublisher;

    public BayServiceImpl(
            BayRepository bayRepository,
            LocationRepository locationRepository,
            ExtCatalogServiceReplicaRepository extCatalogServiceReplicaRepository,
            BaySpecialtyOperationRepository baySpecialtyOperationRepository,
            LocationFactPublisher locationFactPublisher) {
        this.bayRepository = bayRepository;
        this.locationRepository = locationRepository;
        this.extCatalogServiceReplicaRepository = extCatalogServiceReplicaRepository;
        this.baySpecialtyOperationRepository = baySpecialtyOperationRepository;
        this.locationFactPublisher = locationFactPublisher;
    }

    public BayResponse createBay(UUID locationId, BayRequest request) {
        Location location = locationRepository
                .findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Location not found"));

        String name = requireName(request.getName());
        String bayType = normalizeBayType(request.getBayType());
        String status = normalizeStatus(request.getStatus());
        int maxConcurrentVehicles = resolveMaxConcurrentVehicles(request);
        if (maxConcurrentVehicles < 1) {
            throw new IllegalArgumentException("capacity.maxConcurrentVehicles must be >= 1");
        }

        if (bayRepository.existsByLocationIdAndNameIgnoreCase(locationId, name)
                || bayRepository
                        .findByLocationIdAndNormalizedName(locationId, normalizeName(name))
                        .isPresent()) {
            throw new DuplicateResourceException(BAY_NAME_TAKEN);
        }

        // Null means "not stated" and takes the type's default from the specialty map (CAP-325
        // D14); an explicit list — including an explicit empty one — is the caller's own claim and
        // is validated as given. Same null-versus-empty discipline as the rest of the platform.
        List<String> validatedCapabilityCodes = request.getServiceCapabilityCodes() == null
                ? defaultSpecialtyCodesFor(bayType)
                : validateServiceCapabilityIds(request.getServiceCapabilityCodes());

        BayEntity entity = BayEntity.builder()
                .location(location)
                .name(name)
                .normalizedName(normalizeName(name))
                .bayType(bayType)
                .status(status)
                .maxConcurrentVehicles(maxConcurrentVehicles)
                .serviceCapabilityCodes(validatedCapabilityCodes)
                .maxDutyClass(request.getMaxDutyClass())
                .build();

        try {
            BayEntity saved = bayRepository.save(entity);
            // Bulk ingest creates one bay per row through this same method, each in its own
            // transaction, so it emits a fact per created row for free (issue #1668).
            locationFactPublisher.bayChanged(saved);
            return toResponse(saved);
        } catch (OptimisticLockingFailureException exception) {
            throw toBayOptimisticLockException(exception);
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
            page = bayRepository.findByLocationIdAndStatusAndBayType(
                    locationId, normalizedStatus, normalizedBayType, pageable);
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
        BayEntity bay = bayRepository
                .findByIdAndLocationId(bayId, locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Bay not found"));
        return toResponse(bay);
    }

    public BayResponse patchBay(UUID locationId, UUID bayId, BayPatchRequest patch) {
        validateLocationExists(locationId);
        BayEntity existing = bayRepository
                .findByIdAndLocationId(bayId, locationId)
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
            String newBayType = normalizeBayType(patch.getBayType());
            boolean typeChanged = !newBayType.equals(existing.getBayType());
            existing.setBayType(newBayType);
            // A retyped bay whose codes were not also stated re-defaults to the new type's map,
            // otherwise a bay retyped to GENERAL_SERVICE would keep an alignment claim it no
            // longer has the rack for (D14 rule 3: general bays declare nothing).
            if (typeChanged && patch.getServiceCapabilityCodes() == null) {
                existing.setServiceCapabilityCodes(defaultSpecialtyCodesFor(newBayType));
            }
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

        if (patch.getServiceCapabilityCodes() != null) {
            existing.setServiceCapabilityCodes(validateServiceCapabilityIds(patch.getServiceCapabilityCodes()));
        }
        if (patch.getMaxDutyClass() != null) {
            existing.setMaxDutyClass(patch.getMaxDutyClass());
        }

        try {
            BayEntity saved = bayRepository.save(existing);
            // Status transitions (ACTIVE <-> OUT_OF_SERVICE) travel on this same fact; a bay taken
            // out of service keeps its replica row and flips inactive (issue #1668).
            locationFactPublisher.bayChanged(saved);
            return toResponse(saved);
        } catch (OptimisticLockingFailureException exception) {
            throw toBayOptimisticLockException(exception);
        } catch (DataIntegrityViolationException exception) {
            throw toBayConflictException(exception);
        }
    }

    /**
     * Hard-deletes a bay and emits the {@code location.bay.deleted} tombstone (issue #1668).
     *
     * <p>Loads the row before deleting it so the fact can be versioned from its final
     * {@code @Version}, the same load-before-delete shape {@code LocationServiceImpl.deleteLocation}
     * uses: the tombstone publisher needs the entity, not just the id. A bay id that resolves to
     * nothing has no state to version and no delete to announce, so it is a silent no-op rather
     * than an unconditional publish — a caller retrying a delete for an id that never existed must
     * not produce a tombstone every time.
     *
     * <p>Taking a bay out of service is a status change via {@link #patchBay}, not a delete;
     * consumers remove the replica row unconditionally here.
     */
    public boolean deleteBay(UUID locationId, UUID bayId) {
        validateLocationExists(locationId);
        BayEntity existing =
                bayRepository.findByIdAndLocationId(bayId, locationId).orElse(null);
        if (existing == null) {
            return false;
        }
        try {
            bayRepository.delete(existing);
            locationFactPublisher.bayDeleted(existing);
        } catch (OptimisticLockingFailureException exception) {
            throw toBayOptimisticLockException(exception);
        }
        return true;
    }

    /**
     * Translates an optimistic-lock failure into 409, the platform's contract for a version
     * mismatch (ADR-0017 §2), matching {@code LocationServiceImpl.saveLocationInternal}.
     *
     * <p>Reachable only since bays gained a JPA {@code @Version} for the {@code location.bay.*}
     * facts (#1668). Before that, two concurrent patches were last-write-wins and both returned
     * 200; without this translation the loser would now surface as an unmapped 500, because
     * neither this module's handler nor pos-web-common's maps
     * {@code ObjectOptimisticLockingFailureException}. The publisher's flush pulls the failure
     * inside the surrounding try block, which is what makes the omission easy to miss.
     */
    private ResponseStatusException toBayOptimisticLockException(OptimisticLockingFailureException exception) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_FAILED", exception);
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
        Integer capacityValue =
                request.getCapacity() != null ? request.getCapacity().getMaxConcurrentVehicles() : null;
        Integer resolved = capacityValue != null ? capacityValue : request.getMaxConcurrentVehicles();
        if (resolved == null) {
            throw new IllegalArgumentException("capacity.maxConcurrentVehicles is required");
        }
        return resolved;
    }

    /**
     * The specialty codes a bay of this type carries when the caller states none (CAP-325 D14).
     * Empty for {@code GENERAL_SERVICE}, {@code HEAVY_DUTY} and {@code WASH_DETAIL} by
     * construction — the seed gives them no rows — which is exactly "declares nothing".
     */
    private List<String> defaultSpecialtyCodesFor(String bayType) {
        if (baySpecialtyOperationRepository == null) {
            return List.of();
        }
        return baySpecialtyOperationRepository.findByBayType(bayType).stream()
                .map(BaySpecialtyOperationEntity::getOperationCode)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private List<String> validateServiceCapabilityIds(List<String> serviceCapabilityIds) {
        if (serviceCapabilityIds == null || serviceCapabilityIds.isEmpty()) {
            return List.of();
        }
        if (extCatalogServiceReplicaRepository == null) {
            throw new IllegalArgumentException("catalog service replica is not configured");
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

    /**
     * A specialty claim is valid only if it names an <em>active</em> catalog operation code (CAP-325
     * D14), resolved against the {@code ext_catalog_service} replica rather than any synchronous
     * read (ADR-0044 §6). A code whose service pos-catalog has since retired is present in the
     * replica with {@code active = false} and therefore not returned here — so a retired code
     * fails validation the same way an unknown one does, while remaining distinguishable in the
     * replica for anyone who needs to know which it was.
     */
    private Set<String> findServiceCapabilityCodes(Set<String> requestedCodes) {
        Set<String> foundCodes = new LinkedHashSet<>();
        List<ExtCatalogServiceReplica> found =
                extCatalogServiceReplicaRepository.findByOperationCodeInAndActiveIsTrue(requestedCodes);
        if (found == null) {
            return foundCodes;
        }
        for (ExtCatalogServiceReplica service : found) {
            if (service.getOperationCode() == null) {
                continue;
            }
            foundCodes.add(service.getOperationCode().trim().toUpperCase(Locale.ROOT));
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
            throw new IllegalArgumentException("Invalid serviceCapabilityCodes: " + String.join(", ", invalidCodes));
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
                .serviceCapabilityCodes(
                        entity.getServiceCapabilityCodes() == null ? List.of() : entity.getServiceCapabilityCodes())
                .maxDutyClass(entity.getMaxDutyClass())
                .createdAt(entity.getCreatedAt())
                .lastModifiedAt(entity.getUpdatedAt())
                .build();
    }

    private record NormalizedServiceCapabilities(List<String> normalizedCodes, Set<String> invalidCodes) {}
}
