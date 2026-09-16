package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.CoverageRuleRequest;
import com.positivity.location.internal.dto.CoverageRuleResponse;
import com.positivity.location.internal.dto.EligibleMobileUnitResponse;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.entity.MobileUnitCoverageRuleEntity;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.MobileUnitCoverageRuleRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public API for mobile unit management and eligibility evaluation.
 *
 * Issue: #76
 */
@Service
public class MobileUnitServiceImpl implements MobileUnitService {
    private final Clock clock;

    private static final String MOBILE_UNIT_NAME_TAKEN = "MOBILE_UNIT_NAME_TAKEN";
    private static final String MOBILE_UNIT_CONFLICT = "MOBILE_UNIT_CONFLICT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String PATCH_KEY_STATUS = "status";
    private static final String PATCH_KEY_TRAVEL_BUFFER_POLICY_ID = "travelBufferPolicyId";
    private static final String PATCH_KEY_NOTES = "notes";
    private static final String PATCH_KEY_SERVICE_CAPABILITY_CODES = "serviceCapabilityCodes";
    private static final String FIELD_MAX_DISTANCE = "maxDistance";

    protected final MobileUnitRepository mobileUnitRepository;
    protected final MobileUnitCoverageRuleRepository coverageRuleRepository;
    protected final ServiceAreaRepository serviceAreaRepository;
    protected final TravelBufferPolicyRepository travelBufferPolicyRepository;
    protected final ServiceCapabilityCodeValidator serviceCapabilityCodeValidator;
    protected final LocationRepository locationRepository;
    protected final LocationFactPublisher locationFactPublisher;

    public MobileUnitServiceImpl(
            MobileUnitRepository mobileUnitRepository,
            MobileUnitCoverageRuleRepository coverageRuleRepository,
            ServiceAreaRepository serviceAreaRepository,
            TravelBufferPolicyRepository travelBufferPolicyRepository,
            ExtCatalogServiceReplicaRepository extCatalogServiceReplicaRepository,
            LocationRepository locationRepository,
            LocationFactPublisher locationFactPublisher,
            Clock clock) {
        this.clock = clock;
        this.mobileUnitRepository = mobileUnitRepository;
        this.coverageRuleRepository = coverageRuleRepository;
        this.serviceAreaRepository = serviceAreaRepository;
        this.travelBufferPolicyRepository = travelBufferPolicyRepository;
        this.serviceCapabilityCodeValidator = new ServiceCapabilityCodeValidator(extCatalogServiceReplicaRepository);
        this.locationRepository = locationRepository;
        this.locationFactPublisher = locationFactPublisher;
    }

    /**
     * Creates a mobile unit from a generic request map.
     *
     * @param request map payload accepted by story #76 TDD tests
     * @return created mobile unit response
     */
    @Transactional
    public MobileUnitResponse createMobileUnit(Map<String, Object> request) {
        MobileUnitRequest typedRequest = toMobileUnitRequest(request);
        return createMobileUnitInternal(typedRequest);
    }

    /**
     * Creates a mobile unit using a typed request DTO.
     *
     * @param request mobile unit payload
     * @return created mobile unit response
     */
    @Transactional
    public MobileUnitResponse createMobileUnit(MobileUnitRequest request) {
        return createMobileUnitInternal(request);
    }

    private MobileUnitResponse createMobileUnitInternal(MobileUnitRequest request) {
        String normalizedStatus = normalizeStatus(request.getStatus());
        List<String> serviceCapabilityCodes = nonNullList(request.getServiceCapabilityCodes());
        List<CoverageRuleRequest> coverageRules = nonNullList(request.getCoverageRules());

        validateCreateMobileUnitRequest(
                normalizedStatus, request.getTravelBufferPolicyId(), serviceCapabilityCodes, coverageRules);

        // CAP-325 D14: a unit's claim is catalog operation codes, validated against the replica the
        // same way a bay's specialty claim is — one vocabulary for "what can this resource perform".
        Set<String> validatedCodes =
                new LinkedHashSet<>(serviceCapabilityCodeValidator.validate(serviceCapabilityCodes));
        validateDistanceTierRules(coverageRules);
        validateUnitNameUniqueness(request.getBaseLocationId(), request.getName());

        MobileUnitEntity persisted = persistMobileUnitEntity(request, normalizedStatus, validatedCodes);
        if (!coverageRules.isEmpty()) {
            replaceCoverageRulesInternal(persisted.getId(), coverageRules);
        }
        // Published once, after the coverage rules land, so a create-with-rules emits a single
        // fact rather than one per write. Coverage rules are not part of the published payload —
        // replacing them alone therefore emits nothing (issue #1668). Bulk ingest reaches this
        // method one row per transaction, so it is covered here too.
        locationFactPublisher.mobileUnitChanged(persisted);
        return toMobileUnitResponse(persisted);
    }

    private <T> List<T> nonNullList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void validateCreateMobileUnitRequest(
            String normalizedStatus,
            UUID travelBufferPolicyId,
            List<String> serviceCapabilityCodes,
            List<CoverageRuleRequest> coverageRules) {
        if (STATUS_ACTIVE.equals(normalizedStatus)
                && (travelBufferPolicyId == null || serviceCapabilityCodes.isEmpty() || coverageRules.isEmpty())) {
            throw new IllegalArgumentException(
                    "ACTIVE mobile unit requires travelBufferPolicyId, serviceCapabilityCodes, and coverageRules");
        }

        if (travelBufferPolicyId != null
                && travelBufferPolicyRepository.findById(travelBufferPolicyId).isEmpty()) {
            throw new IllegalArgumentException("Unknown travelBufferPolicyId: " + travelBufferPolicyId);
        }
    }

    private void validateDistanceTierRules(List<CoverageRuleRequest> coverageRules) {
        boolean hasDistanceTierRules =
                coverageRules.stream().anyMatch(rule -> "DISTANCE_TIER".equalsIgnoreCase(rule.getRuleType()));
        if (hasDistanceTierRules) {
            validateDistanceTiers(coverageRules.stream().map(this::toTierMap).toList());
        }
    }

    private void validateUnitNameUniqueness(UUID baseLocationId, String name) {
        String unitName = name == null ? "" : name.trim();
        if (baseLocationId != null
                && !unitName.isEmpty()
                && mobileUnitRepository.existsByBaseLocationIdAndNameIgnoreCase(baseLocationId, unitName)) {
            throw new DuplicateResourceException(MOBILE_UNIT_NAME_TAKEN);
        }
    }

    private MobileUnitEntity persistMobileUnitEntity(
            MobileUnitRequest request, String normalizedStatus, Set<String> serviceCapabilityCodes) {
        UUID baseLocationId = request.getBaseLocationId();
        MobileUnitEntity entity = MobileUnitEntity.builder()
                .name(request.getName())
                .baseLocation(
                        baseLocationId != null
                                ? locationRepository.findById(baseLocationId).orElse(null)
                                : null)
                .status(normalizedStatus)
                .travelBufferPolicyId(request.getTravelBufferPolicyId())
                .notes(request.getNotes())
                .serviceCapabilityCodes(serviceCapabilityCodes)
                .build();

        try {
            return mobileUnitRepository.save(entity);
        } catch (OptimisticLockingFailureException exception) {
            throw toMobileUnitOptimisticLockException(exception);
        } catch (DataIntegrityViolationException exception) {
            throw toMobileUnitConflictException(exception);
        }
    }

    /**
     * Validates a unit's specialty claim: every value an active catalog operation code (CAP-325 D14).
     *
     * @param serviceCapabilityCodes codes from the request payload
     */
    public void validateServiceCapabilityCodes(List<?> serviceCapabilityCodes) {
        if (serviceCapabilityCodes == null) {
            return;
        }
        serviceCapabilityCodeValidator.validate(
                serviceCapabilityCodes.stream().map(String::valueOf).toList());
    }

    /**
     * Validates DISTANCE_TIER sequence constraints.
     *
     * @param tiers tier list containing maxDistance values
     */
    public void validateDistanceTiers(List<?> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("Distance tiers must be strictly ascending and end with null catch-all");
        }
        BigDecimal previous = null;
        boolean seenCatchAll = false;
        int index = 0;
        for (Object entry : tiers) {
            index++;
            BigDecimal current = extractMaxDistance(entry);
            if (current == null) {
                if (index != tiers.size()) {
                    throw new IllegalArgumentException(
                            "Distance tiers must be strictly ascending and end with null catch-all");
                }
                seenCatchAll = true;
                continue;
            }
            if (seenCatchAll || (previous != null && current.compareTo(previous) <= 0)) {
                throw new IllegalArgumentException(
                        "Distance tiers must be strictly ascending and end with null catch-all");
            }
            previous = current;
        }
        if (!seenCatchAll) {
            throw new IllegalArgumentException("Distance tiers must be strictly ascending and end with null catch-all");
        }
    }

    /**
     * Returns paginated mobile unit list.
     *
     * @param page zero-based page index
     * @param size page size
     * @return mobile unit page
     */
    @Transactional(readOnly = true)
    public Page<MobileUnitResponse> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return mobileUnitRepository.findAll(pageable).map(this::toMobileUnitResponse);
    }

    /**
     * Finds a mobile unit by ID.
     *
     * @param id mobile unit ID
     * @return optional mobile unit
     */
    @Transactional(readOnly = true)
    public java.util.Optional<MobileUnitResponse> getById(UUID id) {
        return mobileUnitRepository.findById(id).map(this::toMobileUnitResponse);
    }

    /**
     * Applies partial updates to a mobile unit.
     *
     * @param id    mobile unit ID
     * @param patch patch payload
     * @return updated mobile unit response
     */
    @Transactional
    public MobileUnitResponse patch(UUID id, Map<String, Object> patch) {
        MobileUnitEntity entity = mobileUnitRepository.findById(id).orElse(null);
        if (entity == null) {
            return MobileUnitResponse.builder()
                    .id(id)
                    .name((String) patch.get("name"))
                    .status(
                            patch.containsKey(PATCH_KEY_STATUS)
                                    ? normalizeStatus(String.valueOf(patch.get(PATCH_KEY_STATUS)))
                                    : STATUS_INACTIVE)
                    .travelBufferPolicyId(parseUuid(patch.get(PATCH_KEY_TRAVEL_BUFFER_POLICY_ID)))
                    .notes((String) patch.get(PATCH_KEY_NOTES))
                    .updatedAt(Instant.now(clock))
                    .build();
        }

        if (patch.containsKey("name")) {
            entity.setName((String) patch.get("name"));
        }
        if (patch.containsKey(PATCH_KEY_STATUS)) {
            entity.setStatus(normalizeStatus(String.valueOf(patch.get(PATCH_KEY_STATUS))));
        }
        if (patch.containsKey(PATCH_KEY_NOTES)) {
            entity.setNotes((String) patch.get(PATCH_KEY_NOTES));
        }
        if (patch.containsKey(PATCH_KEY_TRAVEL_BUFFER_POLICY_ID)) {
            entity.setTravelBufferPolicyId(parseUuid(patch.get(PATCH_KEY_TRAVEL_BUFFER_POLICY_ID)));
        }
        if (patch.containsKey(PATCH_KEY_SERVICE_CAPABILITY_CODES)) {
            // Replace-set, like a bay's claim: the request is the whole truth, and an empty list is a
            // unit that claims nothing — the way an incomplete unit is completed after creation.
            entity.setServiceCapabilityCodes(new LinkedHashSet<>(serviceCapabilityCodeValidator.validate(
                    stringList(patch.get(PATCH_KEY_SERVICE_CAPABILITY_CODES)))));
        }
        entity.setUpdatedAt(Instant.now(clock));

        MobileUnitEntity saved;
        try {
            saved = mobileUnitRepository.save(entity);
            // Only reached when the row existed: the early return above synthesizes a response
            // without persisting anything, and publishing a fact for a unit that was never written
            // would create a replica row the owner has no record of (issue #1668). Status
            // transitions (ACTIVE <-> INACTIVE) travel on this fact and keep the replica row.
            //
            // Inside the try because the publisher flushes: since #1668 gave this aggregate a
            // @Version, that flush is where a concurrent patch loses the race, and the failure must
            // reach the caller as 409 rather than an unmapped 500.
            locationFactPublisher.mobileUnitChanged(saved);
        } catch (OptimisticLockingFailureException exception) {
            throw toMobileUnitOptimisticLockException(exception);
        } catch (DataIntegrityViolationException exception) {
            throw toMobileUnitConflictException(exception);
        }
        return toMobileUnitResponse(saved);
    }

    /**
     * Hard-deletes a mobile unit and emits the {@code location.mobile-unit.deleted} tombstone
     * (issue #1668).
     *
     * <p>Loads the row before deleting it so the fact can be versioned from its final
     * {@code @Version} — the load-before-delete shape {@code LocationServiceImpl.deleteLocation}
     * uses. An id that resolves to nothing is a silent no-op: there is no state to version and no
     * delete to announce, and a retried delete for an id that never existed must not publish a
     * tombstone every time.
     *
     * <p>Coverage rules are removed first because {@code mobile_unit_coverage_rules} carries a
     * plain foreign key to {@code mobile_units} with no cascade, so deleting the unit while rules
     * still reference it fails on the constraint. The {@code serviceCapabilityCodes} element
     * collection is owned by the entity, so Hibernate clears it itself.
     *
     * <p>Standing a unit down is a status change via {@link #patch}, not a delete; consumers remove
     * the replica row unconditionally here.
     */
    @Transactional
    public boolean deleteMobileUnit(UUID id) {
        MobileUnitEntity existing = mobileUnitRepository.findById(id).orElse(null);
        if (existing == null) {
            return false;
        }
        try {
            coverageRuleRepository.deleteByMobileUnit_Id(id);
            mobileUnitRepository.delete(existing);
            locationFactPublisher.mobileUnitDeleted(existing);
        } catch (OptimisticLockingFailureException exception) {
            throw toMobileUnitOptimisticLockException(exception);
        }
        return true;
    }

    /**
     * Replaces all coverage rules for a given mobile unit atomically.
     *
     * @param id    mobile unit ID
     * @param rules replacement rules
     * @return persisted rules ordered by priority
     */
    @Transactional
    public List<CoverageRuleResponse> replaceCoverageRules(UUID id, List<CoverageRuleRequest> rules) {
        List<CoverageRuleRequest> typedRules = rules == null ? List.of() : rules;
        return replaceCoverageRulesInternal(id, typedRules);
    }

    private List<CoverageRuleResponse> replaceCoverageRulesInternal(UUID id, List<CoverageRuleRequest> rules) {
        MobileUnitEntity unit = mobileUnitRepository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mobile unit not found"));

        coverageRuleRepository.deleteByMobileUnit_Id(id);

        List<MobileUnitCoverageRuleEntity> entities = new ArrayList<>();
        for (CoverageRuleRequest rule : rules) {
            ServiceAreaEntity serviceArea = null;
            if (rule.getServiceAreaId() != null) {
                serviceArea =
                        serviceAreaRepository.findById(rule.getServiceAreaId()).orElse(null);
            }
            entities.add(MobileUnitCoverageRuleEntity.builder()
                    .mobileUnit(unit)
                    .serviceArea(serviceArea)
                    .ruleType(rule.getRuleType())
                    .priority(rule.getPriority() == null ? 0 : rule.getPriority())
                    .validFrom(rule.getValidFrom())
                    .validTo(rule.getValidTo())
                    .maxDistance(rule.getMaxDistance())
                    .build());
        }
        List<MobileUnitCoverageRuleEntity> saved = coverageRuleRepository.saveAll(entities);
        return saved.stream().map(this::toCoverageRuleResponse).toList();
    }

    /**
     * Replaces all coverage rules from map payload.
     *
     * @param id          mobile unit identifier
     * @param rulePayload rules from request
     * @return persisted rule responses
     */
    @Transactional
    public List<CoverageRuleResponse> replaceCoverageRules(String id, List<Map<String, Object>> rulePayload) {
        UUID unitId = parseUuid(id);
        List<CoverageRuleRequest> typedRules = rulePayload == null
                ? List.of()
                : rulePayload.stream().map(this::toCoverageRuleRequest).toList();
        return replaceCoverageRulesInternal(unitId, typedRules);
    }

    /**
     * Returns coverage rules for a mobile unit.
     *
     * @param id mobile unit identifier
     * @return ordered coverage rules
     */
    @Transactional(readOnly = true)
    public List<CoverageRuleResponse> getCoverageRules(UUID id) {
        return coverageRuleRepository.findByMobileUnit_IdOrderByPriorityAsc(id).stream()
                .map(this::toCoverageRuleResponse)
                .toList();
    }

    /**
     * Finds eligible active mobile units for a service request.
     *
     * @param postalCode  postal code
     * @param countryCode country code
     * @param at          effective time
     * @return eligible units ordered by priority ascending
     */
    @Transactional(readOnly = true)
    public List<EligibleMobileUnitResponse> findEligibleMobileUnits(String postalCode, String countryCode, Instant at) {
        LocalDate atDate = at == null
                ? LocalDate.now(ZoneOffset.UTC)
                : at.atZone(ZoneOffset.UTC).toLocalDate();
        List<MobileUnitCoverageRuleEntity> rules =
                coverageRuleRepository.findEligibleCoverageRules(postalCode, countryCode, atDate);

        Map<UUID, EligibleMobileUnitResponse> ordered = new LinkedHashMap<>();
        for (MobileUnitCoverageRuleEntity rule : rules) {
            MobileUnitEntity unit = rule.getMobileUnit();
            if (unit == null || !STATUS_ACTIVE.equalsIgnoreCase(unit.getStatus())) {
                continue;
            }
            ordered.putIfAbsent(
                    unit.getId(),
                    EligibleMobileUnitResponse.builder()
                            .id(unit.getId())
                            .name(unit.getName())
                            .baseLocationId(unit.getBaseLocationId())
                            .priority(rule.getPriority())
                            .build());
        }
        return new ArrayList<>(ordered.values());
    }

    private MobileUnitRequest toMobileUnitRequest(Map<String, Object> request) {
        if (request == null) {
            return MobileUnitRequest.builder().build();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawCoverage =
                (List<Map<String, Object>>) request.getOrDefault("coverageRules", Collections.emptyList());
        List<String> rawServiceCapabilityCodes = stringList(request.get(PATCH_KEY_SERVICE_CAPABILITY_CODES));
        return MobileUnitRequest.builder()
                .name((String) request.get("name"))
                .baseLocationId(parseUuid(request.get("baseLocationId")))
                .status(request.get(PATCH_KEY_STATUS) == null ? null : String.valueOf(request.get(PATCH_KEY_STATUS)))
                .travelBufferPolicyId(parseUuid(request.get(PATCH_KEY_TRAVEL_BUFFER_POLICY_ID)))
                .notes((String) request.get(PATCH_KEY_NOTES))
                .serviceCapabilityCodes(rawServiceCapabilityCodes)
                .coverageRules(
                        rawCoverage.stream().map(this::toCoverageRuleRequest).toList())
                .build();
    }

    private CoverageRuleRequest toCoverageRuleRequest(Map<String, Object> source) {
        return CoverageRuleRequest.builder()
                .serviceAreaId(parseUuid(source.get("serviceAreaId")))
                .ruleType(source.get("ruleType") == null ? null : String.valueOf(source.get("ruleType")))
                .priority(parseInteger(source.get("priority")))
                .validFrom(parseLocalDate(source.get("validFrom")))
                .validTo(parseLocalDate(source.get("validTo")))
                .maxDistance(parseBigDecimal(source.get(FIELD_MAX_DISTANCE)))
                .build();
    }

    private Map<String, Object> toTierMap(CoverageRuleRequest request) {
        Map<String, Object> map = new HashMap<>();
        map.put(FIELD_MAX_DISTANCE, request.getMaxDistance());
        return map;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private MobileUnitResponse toMobileUnitResponse(MobileUnitEntity entity) {
        return MobileUnitResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .baseLocationId(entity.getBaseLocationId())
                .status(entity.getStatus())
                .travelBufferPolicyId(entity.getTravelBufferPolicyId())
                .notes(entity.getNotes())
                .serviceCapabilityCodes(
                        entity.getServiceCapabilityCodes() == null
                                ? List.of()
                                : List.copyOf(entity.getServiceCapabilityCodes()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private CoverageRuleResponse toCoverageRuleResponse(MobileUnitCoverageRuleEntity entity) {
        return CoverageRuleResponse.builder()
                .id(entity.getId())
                .mobileUnitId(
                        entity.getMobileUnit() == null
                                ? null
                                : entity.getMobileUnit().getId())
                .serviceAreaId(
                        entity.getServiceArea() == null
                                ? null
                                : entity.getServiceArea().getId())
                .ruleType(entity.getRuleType())
                .priority(entity.getPriority())
                .validFrom(entity.getValidFrom())
                .validTo(entity.getValidTo())
                .maxDistance(entity.getMaxDistance())
                .build();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_INACTIVE;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal extractMaxDistance(Object entry) {
        if (entry == null) {
            return null;
        }
        if (entry instanceof Map<?, ?> map) {
            return parseBigDecimal(map.get(FIELD_MAX_DISTANCE));
        }
        if (entry instanceof CoverageRuleRequest request) {
            return request.getMaxDistance();
        }
        return parseBigDecimal(entry);
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private LocalDate parseLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Translates an optimistic-lock failure into 409, the platform's contract for a version
     * mismatch (ADR-0017 §2), matching {@code LocationServiceImpl.saveLocationInternal}.
     *
     * <p>Reachable only since mobile units gained a JPA {@code @Version} for the
     * {@code location.mobile-unit.*} facts (#1668): before that, concurrent patches were
     * last-write-wins. Without this translation the loser would surface as an unmapped 500, since
     * neither this module's handler nor pos-web-common's maps
     * {@code ObjectOptimisticLockingFailureException}.
     */
    private ResponseStatusException toMobileUnitOptimisticLockException(OptimisticLockingFailureException exception) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_FAILED", exception);
    }

    private DuplicateResourceException toMobileUnitConflictException(DataIntegrityViolationException exception) {
        if (isNameConstraintViolation(exception)) {
            return new DuplicateResourceException(MOBILE_UNIT_NAME_TAKEN);
        }
        return new DuplicateResourceException(MOBILE_UNIT_CONFLICT);
    }

    private boolean isNameConstraintViolation(Throwable throwable) {
        String details = lowerCaseMessages(throwable);
        return details.contains("uq_mobile_unit_base_location_lower_name")
                || details.contains("uq_mobile_units_base_location_name")
                || details.contains("base_location_id")
                || details.contains("lower(name)");
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
}
