package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidServiceCapabilityCodesException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.BayRepository;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.location.internal.repository.LocationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private static final String STATUS_ACTIVE = LifecycleStatusSupport.ACTIVE;
    private static final String STATUS_OUT_OF_SERVICE = LifecycleStatusSupport.OUT_OF_SERVICE;
    private static final String STATUS_RETIRED = LifecycleStatusSupport.RETIRED;
    /** DECISION-LOCATION-026 rule 5: bay lists sort by displayOrder (nulls last), then name. */
    private static final Sort BAY_LIST_ORDER =
            Sort.by(Sort.Order.asc("displayOrder").nullsLast(), Sort.Order.asc("name"));

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
        String outOfServiceReason = LifecycleStatusSupport.normalizeReason(request.getOutOfServiceReason());
        String outOfServiceNote = request.getOutOfServiceNote();
        LifecycleStatusSupport.requireNoteLength(outOfServiceNote);
        Instant expectedReturnAt = request.getExpectedReturnAt();
        if (!STATUS_OUT_OF_SERVICE.equals(status)) {
            // Only OUT_OF_SERVICE carries these fields; a caller naming them for another status has
            // them silently dropped rather than stored inert (DECISION-LOCATION-026 rule 4).
            outOfServiceReason = null;
            outOfServiceNote = null;
            expectedReturnAt = null;
        }
        LifecycleStatusSupport.requireReasonWhenOutOfService(status, outOfServiceReason, outOfServiceNote);

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
                : validateServiceCapabilityCodes(request.getServiceCapabilityCodes());

        BayEntity entity = BayEntity.builder()
                .location(location)
                .name(name)
                .normalizedName(normalizeName(name))
                .bayType(bayType)
                .status(status)
                .maxConcurrentVehicles(maxConcurrentVehicles)
                .serviceCapabilityCodes(validatedCapabilityCodes)
                .maxDutyClass(request.getMaxDutyClass())
                .outOfServiceReason(outOfServiceReason)
                .outOfServiceNote(outOfServiceNote)
                .expectedReturnAt(expectedReturnAt)
                .displayOrder(request.getDisplayOrder())
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
        // DECISION-LOCATION-026 rule 5: displayOrder (nulls last), then name — imposed here rather
        // than left to the caller's Pageable, which never carries a sort of its own.
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), BAY_LIST_ORDER);

        Page<BayEntity> page;
        if (normalizedStatus != null && normalizedBayType != null) {
            page = bayRepository.findByLocationIdAndStatusAndBayType(
                    locationId, normalizedStatus, normalizedBayType, ordered);
        } else if (normalizedStatus != null) {
            page = bayRepository.findByLocationIdAndStatus(locationId, normalizedStatus, ordered);
        } else if (normalizedBayType != null) {
            // No explicit status filter: default lists hide RETIRED (DECISION-LOCATION-008/026).
            page = bayRepository.findByLocationIdAndBayTypeAndStatusNot(
                    locationId, normalizedBayType, STATUS_RETIRED, ordered);
        } else {
            page = bayRepository.findByLocationIdAndStatusNot(locationId, STATUS_RETIRED, ordered);
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
        applyOutOfServiceFields(existing, patch);

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
            existing.setServiceCapabilityCodes(validateServiceCapabilityCodes(patch.getServiceCapabilityCodes()));
        }
        if (patch.getMaxDutyClass() != null) {
            existing.setMaxDutyClass(patch.getMaxDutyClass());
        }
        if (patch.getDisplayOrder() != null) {
            existing.setDisplayOrder(patch.getDisplayOrder());
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
     * Retires a bay (DECISION-LOCATION-026 rule 1, issue #2264): the row stays and {@code status}
     * becomes {@code RETIRED}. Nothing is hard-deleted, so consumers keep their replica row and an
     * appointment or workorder that already names this bay still resolves to it.
     *
     * <p>Idempotent: retiring an already-{@code RETIRED} bay is a normal update, not an error —
     * a retried delete for the same bay must not fail just because the first one already landed.
     * A bay id that resolves to nothing has no state to change and publishes nothing, the same
     * silent-no-op contract the former hard delete kept.
     *
     * <p>{@code RETIRED} is reversible ({@code patchBay} back to {@code ACTIVE} or {@code
     * OUT_OF_SERVICE}); it carries no error code of its own.
     */
    public boolean deleteBay(UUID locationId, UUID bayId) {
        validateLocationExists(locationId);
        BayEntity existing =
                bayRepository.findByIdAndLocationId(bayId, locationId).orElse(null);
        if (existing == null) {
            return false;
        }
        existing.setStatus(STATUS_RETIRED);
        try {
            BayEntity saved = bayRepository.save(existing);
            locationFactPublisher.bayChanged(saved);
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
        return LifecycleStatusSupport.normalizeStatus(value, STATUS_ACTIVE);
    }

    /**
     * Applies {@code patch}'s out-of-service fields to {@code existing} (DECISION-LOCATION-026 rule
     * 4). A patched field with a non-null value replaces the stored one; an omitted (null) field
     * leaves the stored value unchanged — except that a status patch resolving to {@code ACTIVE}
     * always clears all three, regardless of what the patch also sent. The resulting state is
     * validated only when the resulting status is {@code OUT_OF_SERVICE}: a reason must be present,
     * and {@code OTHER} must carry a non-blank note.
     */
    private void applyOutOfServiceFields(BayEntity existing, BayPatchRequest patch) {
        if (patch.getOutOfServiceReason() != null) {
            existing.setOutOfServiceReason(LifecycleStatusSupport.normalizeReason(patch.getOutOfServiceReason()));
        }
        if (patch.getOutOfServiceNote() != null) {
            LifecycleStatusSupport.requireNoteLength(patch.getOutOfServiceNote());
            existing.setOutOfServiceNote(patch.getOutOfServiceNote());
        }
        if (patch.getExpectedReturnAt() != null) {
            existing.setExpectedReturnAt(patch.getExpectedReturnAt());
        }
        if (STATUS_ACTIVE.equals(existing.getStatus())) {
            existing.setOutOfServiceReason(null);
            existing.setOutOfServiceNote(null);
            existing.setExpectedReturnAt(null);
        }
        LifecycleStatusSupport.requireReasonWhenOutOfService(
                existing.getStatus(), existing.getOutOfServiceReason(), existing.getOutOfServiceNote());
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
        List<String> seeded = baySpecialtyOperationRepository.findByBayType(bayType).stream()
                .map(BaySpecialtyOperationEntity::getOperationCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        try {
            // The map is a seed, not caller input, and it is held to the same rule: a row naming a
            // code the catalog has retired or never published must not enter a bay as a claim the
            // API would refuse from a caller (#2045 review). Named as the seed's defect, so the seed
            // gets fixed rather than the request.
            return validateServiceCapabilityCodes(seeded);
        } catch (InvalidServiceCapabilityCodesException exception) {
            throw new InvalidServiceCapabilityCodesException(
                    "Specialty map for bayType " + bayType + " names codes that are not active catalog operation"
                            + " codes: " + String.join(", ", exception.getInvalidCodes()),
                    exception.getInvalidCodes());
        }
    }

    /**
     * A caller's explicit claim, read by the one validator bays share with mobile units (CAP-325
     * D14): active catalog operation codes only, normalized and de-duplicated; 422 otherwise.
     */
    private List<String> validateServiceCapabilityCodes(List<String> serviceCapabilityCodes) {
        return new ServiceCapabilityCodeValidator(extCatalogServiceReplicaRepository).validate(serviceCapabilityCodes);
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
                .outOfServiceReason(entity.getOutOfServiceReason())
                .outOfServiceNote(entity.getOutOfServiceNote())
                .expectedReturnAt(entity.getExpectedReturnAt())
                .displayOrder(entity.getDisplayOrder())
                .createdAt(entity.getCreatedAt())
                .lastModifiedAt(entity.getUpdatedAt())
                .build();
    }
}
