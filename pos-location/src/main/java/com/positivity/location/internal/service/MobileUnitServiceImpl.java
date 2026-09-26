package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.CoverageRuleRequest;
import com.positivity.location.internal.dto.CoverageRuleResponse;
import com.positivity.location.internal.dto.EligibleMobileUnitResponse;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.MobileUnitCoverageRuleEntity;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidFieldException;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
    static final String ACTIVE_UNIT_INCOMPLETE =
            "ACTIVE mobile unit requires travelBufferPolicyId, serviceCapabilityCodes, and coverageRules";
    private static final String FIELD_MAX_DISTANCE = "maxDistance";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_BASE_LOCATION_ID = "baseLocationId";
    private static final String FIELD_COVERAGE_RULES = "coverageRules";
    private static final String FIELD_RULES = "rules";
    static final String RULE_TYPE_SERVICE_AREA = "SERVICE_AREA";
    static final String RULE_TYPE_DISTANCE_TIER = "DISTANCE_TIER";
    private static final Set<String> RULE_TYPES = Set.of(RULE_TYPE_SERVICE_AREA, RULE_TYPE_DISTANCE_TIER);
    private static final Set<String> STATUSES = Set.of(STATUS_ACTIVE, STATUS_INACTIVE);
    static final String TRAVEL_BUFFER_POLICY_NOT_FOUND = "TRAVEL_BUFFER_POLICY_NOT_FOUND";
    static final String LOCATION_NOT_FOUND = "LOCATION_NOT_FOUND";
    static final String SERVICE_AREA_NOT_FOUND = "SERVICE_AREA_NOT_FOUND";
    private static final String MOBILE_UNIT_NOT_FOUND = "Mobile unit not found";
    private static final String DISTANCE_TIERS_INVALID =
            "DISTANCE_TIER rules must be strictly ascending by maxDistance and end with one null catch-all";
    private static final String STATUS_INVALID = "status must be ACTIVE or INACTIVE";
    /** List order: stable across pages, so a unit cannot move between pages or appear on two. */
    private static final Sort LIST_ORDER = Sort.by(Sort.Order.asc(FIELD_NAME), Sort.Order.asc("id"));

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
        // 400s first (the request is malformed), then 422s (well-formed but refers to nothing or
        // is incomplete), then 409 (collides with another unit) — #2252.
        String name = requireName(request.getName());
        UUID baseLocationId = request.getBaseLocationId();
        if (baseLocationId == null) {
            throw InvalidFieldException.invalid(FIELD_BASE_LOCATION_ID, "baseLocationId is required");
        }
        String normalizedStatus = normalizeCreateStatus(request.getStatus());
        List<String> serviceCapabilityCodes = nonNullList(request.getServiceCapabilityCodes());
        List<CoverageRuleRequest> coverageRules =
                validateCoverageRules(nonNullList(request.getCoverageRules()), FIELD_COVERAGE_RULES);

        validateCreateMobileUnitRequest(
                normalizedStatus, request.getTravelBufferPolicyId(), serviceCapabilityCodes, coverageRules);
        Location baseLocation = locationRepository
                .findById(baseLocationId)
                .orElseThrow(() -> InvalidFieldException.unknownReference(
                        LOCATION_NOT_FOUND,
                        FIELD_BASE_LOCATION_ID,
                        "baseLocationId does not reference an existing location"));
        Map<UUID, ServiceAreaEntity> serviceAreas = resolveServiceAreas(coverageRules, FIELD_COVERAGE_RULES);

        // CAP-325 D14: a unit's claim is catalog operation codes, validated against the replica the
        // same way a bay's specialty claim is — one vocabulary for "what can this resource perform".
        Set<String> validatedCodes =
                new LinkedHashSet<>(serviceCapabilityCodeValidator.validate(serviceCapabilityCodes));
        if (mobileUnitRepository.existsByBaseLocationIdAndNameIgnoreCase(baseLocationId, name)) {
            throw new DuplicateResourceException(MOBILE_UNIT_NAME_TAKEN);
        }

        MobileUnitEntity persisted =
                persistMobileUnitEntity(request, name, baseLocation, normalizedStatus, validatedCodes);
        if (!coverageRules.isEmpty()) {
            saveCoverageRules(persisted, coverageRules, serviceAreas);
        }
        // Published once, after the coverage rules land, so a create-with-rules emits a single
        // fact rather than one per write. Coverage rules are not part of the published payload —
        // replacing them alone therefore emits nothing (issue #1668). Bulk ingest reaches this
        // method one row per transaction, so it is covered here too.
        locationFactPublisher.mobileUnitChanged(persisted);
        return toMobileUnitResponse(persisted);
    }

    /** 422 when an ACTIVE unit lacks a travel buffer policy, a capability claim or coverage rules. */
    private void requireCompleteWhenActive(MobileUnitEntity entity) {
        if (!STATUS_ACTIVE.equals(entity.getStatus())) {
            return;
        }
        boolean complete = entity.getTravelBufferPolicyId() != null
                && !entity.getServiceCapabilityCodes().isEmpty()
                && !coverageRuleRepository
                        .findByMobileUnit_IdOrderByPriorityAsc(entity.getId())
                        .isEmpty();
        if (!complete) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ACTIVE_UNIT_INCOMPLETE);
        }
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
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ACTIVE_UNIT_INCOMPLETE);
        }
        requireTravelBufferPolicyExists(travelBufferPolicyId);
    }

    private void requireTravelBufferPolicyExists(UUID travelBufferPolicyId) {
        if (travelBufferPolicyId != null
                && travelBufferPolicyRepository.findById(travelBufferPolicyId).isEmpty()) {
            throw InvalidFieldException.unknownReference(
                    TRAVEL_BUFFER_POLICY_NOT_FOUND,
                    PATCH_KEY_TRAVEL_BUFFER_POLICY_ID,
                    "travelBufferPolicyId does not reference an existing travel buffer policy");
        }
    }

    /** 400 for a missing, blank or non-text name; otherwise the trimmed name. */
    private static String requireName(Object name) {
        if (!(name instanceof String text) || text.isBlank()) {
            throw InvalidFieldException.invalid(FIELD_NAME, "name is required and must not be blank");
        }
        return text.trim();
    }

    /**
     * Checks each rule's shape (#2248): a ruleType of SERVICE_AREA or DISTANCE_TIER, a
     * serviceAreaId (a rule without one never matches an address), a non-negative priority and
     * maxDistance, a validity window that does not end before it starts, and — across the
     * DISTANCE_TIER rules — strictly ascending tiers ending in one catch-all.
     *
     * @param rules rules as the client sent them
     * @param field the request field holding the list ({@code coverageRules} or {@code rules})
     * @return the rules with ruleType upper-cased
     */
    private List<CoverageRuleRequest> validateCoverageRules(List<CoverageRuleRequest> rules, String field) {
        List<CoverageRuleRequest> normalized = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            CoverageRuleRequest rule = rules.get(i);
            String prefix = field + "[" + i + "].";
            if (rule == null) {
                throw InvalidFieldException.invalid(field + "[" + i + "]", "coverage rule must be an object");
            }
            String ruleType = rule.getRuleType() == null
                    ? null
                    : rule.getRuleType().trim().toUpperCase(Locale.ROOT);
            if (ruleType == null || !RULE_TYPES.contains(ruleType)) {
                throw InvalidFieldException.invalid(
                        prefix + "ruleType", "ruleType must be SERVICE_AREA or DISTANCE_TIER");
            }
            if (rule.getServiceAreaId() == null) {
                throw InvalidFieldException.invalid(prefix + "serviceAreaId", "serviceAreaId is required");
            }
            if (rule.getPriority() != null && rule.getPriority() < 0) {
                throw InvalidFieldException.invalid(prefix + "priority", "priority must not be negative");
            }
            if (rule.getMaxDistance() != null && rule.getMaxDistance().signum() < 0) {
                throw InvalidFieldException.invalid(prefix + FIELD_MAX_DISTANCE, "maxDistance must not be negative");
            }
            if (rule.getValidFrom() != null
                    && rule.getValidTo() != null
                    && rule.getValidTo().isBefore(rule.getValidFrom())) {
                throw InvalidFieldException.invalid(prefix + "validTo", "validTo must not be before validFrom");
            }
            normalized.add(rule.toBuilder().ruleType(ruleType).build());
        }
        List<CoverageRuleRequest> tiers = normalized.stream()
                .filter(rule -> RULE_TYPE_DISTANCE_TIER.equals(rule.getRuleType()))
                .toList();
        if (!tiers.isEmpty()) {
            validateDistanceTiers(tiers.stream().map(this::toTierMap).toList(), field);
        }
        return normalized;
    }

    /** 422 naming the first rule whose serviceAreaId resolves to nothing; otherwise the areas by id. */
    private Map<UUID, ServiceAreaEntity> resolveServiceAreas(List<CoverageRuleRequest> rules, String field) {
        if (rules.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids =
                rules.stream().map(CoverageRuleRequest::getServiceAreaId).collect(Collectors.toSet());
        Map<UUID, ServiceAreaEntity> found = serviceAreaRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ServiceAreaEntity::getId, Function.identity()));
        for (int i = 0; i < rules.size(); i++) {
            if (!found.containsKey(rules.get(i).getServiceAreaId())) {
                throw InvalidFieldException.unknownReference(
                        SERVICE_AREA_NOT_FOUND,
                        field + "[" + i + "].serviceAreaId",
                        "serviceAreaId does not reference an existing service area");
            }
        }
        return found;
    }

    private MobileUnitEntity persistMobileUnitEntity(
            MobileUnitRequest request,
            String name,
            Location baseLocation,
            String normalizedStatus,
            Set<String> serviceCapabilityCodes) {
        MobileUnitEntity entity = MobileUnitEntity.builder()
                .name(name)
                .baseLocation(baseLocation)
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
     * Validates DISTANCE_TIER sequence constraints: strictly ascending maxDistance, ending with
     * exactly one null catch-all. 400 {@code VALIDATION_ERROR} on {@code coverageRules} otherwise.
     *
     * @param tiers tier list containing maxDistance values
     */
    public void validateDistanceTiers(List<?> tiers) {
        validateDistanceTiers(tiers, FIELD_COVERAGE_RULES);
    }

    private void validateDistanceTiers(List<?> tiers, String field) {
        if (tiers == null || tiers.isEmpty()) {
            throw InvalidFieldException.invalid(field, DISTANCE_TIERS_INVALID);
        }
        BigDecimal previous = null;
        boolean seenCatchAll = false;
        int index = 0;
        for (Object entry : tiers) {
            index++;
            BigDecimal current = extractMaxDistance(entry);
            if (current == null) {
                if (index != tiers.size()) {
                    throw InvalidFieldException.invalid(field, DISTANCE_TIERS_INVALID);
                }
                seenCatchAll = true;
                continue;
            }
            if (previous != null && current.compareTo(previous) <= 0) {
                throw InvalidFieldException.invalid(field, DISTANCE_TIERS_INVALID);
            }
            previous = current;
        }
        if (!seenCatchAll) {
            throw InvalidFieldException.invalid(field, DISTANCE_TIERS_INVALID);
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
        return list(page, size, null, null, false);
    }

    /**
     * Returns a page of mobile units, optionally narrowed to one base location and/or status, each
     * optionally carrying its coverage rules (#2253). Ordered by name, then id.
     *
     * @param page                 zero-based page index
     * @param size                 page size
     * @param baseLocationId       only units based here; {@code null} for every location
     * @param status               only units in this status (ACTIVE or INACTIVE, any case);
     *                             {@code null} or blank for every status
     * @param includeCoverageRules whether each unit carries its coverage rules, read for the
     *                             whole page in one query
     * @return mobile unit page
     */
    @Transactional(readOnly = true)
    @NonNull
    public Page<MobileUnitResponse> list(
            int page, int size, @Nullable UUID baseLocationId, @Nullable String status, boolean includeCoverageRules) {
        String statusFilter = normalizeStatusFilter(status);
        Pageable pageable = PageRequest.of(page, size, LIST_ORDER);
        Page<MobileUnitEntity> units;
        if (baseLocationId != null && statusFilter != null) {
            units = mobileUnitRepository.findByBaseLocation_IdAndStatus(baseLocationId, statusFilter, pageable);
        } else if (baseLocationId != null) {
            units = mobileUnitRepository.findByBaseLocation_Id(baseLocationId, pageable);
        } else if (statusFilter != null) {
            units = mobileUnitRepository.findByStatus(statusFilter, pageable);
        } else {
            units = mobileUnitRepository.findAll(pageable);
        }
        if (!includeCoverageRules) {
            return units.map(this::toMobileUnitResponse);
        }
        Map<UUID, List<CoverageRuleResponse>> rulesByUnit = coverageRulesByUnit(
                units.getContent().stream().map(MobileUnitEntity::getId).toList());
        return units.map(unit -> {
            MobileUnitResponse response = toMobileUnitResponse(unit);
            response.setCoverageRules(rulesByUnit.getOrDefault(unit.getId(), List.of()));
            return response;
        });
    }

    @NonNull
    private Map<UUID, List<CoverageRuleResponse>> coverageRulesByUnit(@NonNull Collection<UUID> unitIds) {
        if (unitIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CoverageRuleResponse>> byUnit = new HashMap<>();
        for (MobileUnitCoverageRuleEntity rule :
                coverageRuleRepository.findByMobileUnit_IdInOrderByPriorityAsc(unitIds)) {
            CoverageRuleResponse response = toCoverageRuleResponse(rule);
            byUnit.computeIfAbsent(response.getMobileUnitId(), id -> new ArrayList<>())
                    .add(response);
        }
        return byUnit;
    }

    /** {@code null} for no status filter; otherwise the upper-cased status, or 400 when unknown. */
    @Nullable
    private static String normalizeStatusFilter(@Nullable String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw InvalidFieldException.invalid(PATCH_KEY_STATUS, STATUS_INVALID);
        }
        return normalized;
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
     * <p>Every recognised key is checked before anything is written (#2252): a name must be
     * non-blank text and not taken at the unit's base location; status must be ACTIVE or INACTIVE
     * (never null); notes must be text or null; travelBufferPolicyId must be null (clears it) or
     * the id of an existing policy; serviceCapabilityCodes must be an array.
     *
     * @param id    mobile unit ID
     * @param patch patch payload
     * @return updated mobile unit response
     * @throws ResourceNotFoundException when no unit has this id (404)
     */
    @Transactional
    public MobileUnitResponse patch(UUID id, Map<String, Object> patch) {
        MobileUnitEntity entity = mobileUnitRepository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MOBILE_UNIT_NOT_FOUND));
        Map<String, Object> changes = patch == null ? Map.of() : patch;

        if (changes.containsKey(FIELD_NAME)) {
            String name = requireName(changes.get(FIELD_NAME));
            UUID baseLocationId = entity.getBaseLocationId();
            if (baseLocationId != null
                    && !name.equalsIgnoreCase(entity.getName())
                    && mobileUnitRepository.existsByBaseLocationIdAndNameIgnoreCaseAndIdNot(
                            baseLocationId, name, entity.getId())) {
                throw new DuplicateResourceException(MOBILE_UNIT_NAME_TAKEN);
            }
            entity.setName(name);
        }
        if (changes.containsKey(PATCH_KEY_STATUS)) {
            entity.setStatus(requireStatus(changes.get(PATCH_KEY_STATUS)));
        }
        if (changes.containsKey(PATCH_KEY_NOTES)) {
            Object notes = changes.get(PATCH_KEY_NOTES);
            if (notes != null && !(notes instanceof String)) {
                throw InvalidFieldException.invalid(PATCH_KEY_NOTES, "notes must be text or null");
            }
            entity.setNotes((String) notes);
        }
        if (changes.containsKey(PATCH_KEY_TRAVEL_BUFFER_POLICY_ID)) {
            UUID policyId =
                    parseUuidField(changes.get(PATCH_KEY_TRAVEL_BUFFER_POLICY_ID), PATCH_KEY_TRAVEL_BUFFER_POLICY_ID);
            requireTravelBufferPolicyExists(policyId);
            entity.setTravelBufferPolicyId(policyId);
        }
        if (changes.containsKey(PATCH_KEY_SERVICE_CAPABILITY_CODES)) {
            if (!(changes.get(PATCH_KEY_SERVICE_CAPABILITY_CODES) instanceof List<?>)) {
                throw InvalidFieldException.invalid(
                        PATCH_KEY_SERVICE_CAPABILITY_CODES, "serviceCapabilityCodes must be an array");
            }
            // Replace-set, like a bay's claim: the request is the whole truth, and an empty list is a
            // unit that claims nothing — the way an incomplete unit is completed after creation.
            entity.setServiceCapabilityCodes(new LinkedHashSet<>(serviceCapabilityCodeValidator.validate(
                    stringList(changes.get(PATCH_KEY_SERVICE_CAPABILITY_CODES)))));
        }
        // The merged state, not the patch alone, has to satisfy what create demands of an ACTIVE
        // unit: a status flip, a cleared claim or a dropped policy each leave it incomplete
        // otherwise (#2045 review). Coverage rules live in their own table, so they are read back.
        requireCompleteWhenActive(entity);
        entity.setUpdatedAt(Instant.now(clock));

        MobileUnitEntity saved;
        try {
            saved = mobileUnitRepository.save(entity);
            // Status transitions (ACTIVE <-> INACTIVE) travel on this fact and keep the replica row
            // (issue #1668). An unknown id never gets here: it is a 404 above (#2252).
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
     * <p>The replacement set is held to what create demands (#2248): each rule's shape, the
     * DISTANCE_TIER sequence, every serviceAreaId resolving, and an ACTIVE unit keeping at least
     * one rule. Everything is checked before the existing rules are touched.
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
                .orElseThrow(() -> new ResourceNotFoundException(MOBILE_UNIT_NOT_FOUND));

        List<CoverageRuleRequest> normalized = validateCoverageRules(rules, FIELD_RULES);
        if (normalized.isEmpty() && STATUS_ACTIVE.equals(unit.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ACTIVE_UNIT_INCOMPLETE);
        }
        Map<UUID, ServiceAreaEntity> serviceAreas = resolveServiceAreas(normalized, FIELD_RULES);
        return saveCoverageRules(unit, normalized, serviceAreas);
    }

    /** Deletes the unit's rules and saves the already-validated replacement set. */
    private List<CoverageRuleResponse> saveCoverageRules(
            MobileUnitEntity unit, List<CoverageRuleRequest> rules, Map<UUID, ServiceAreaEntity> serviceAreas) {
        coverageRuleRepository.deleteByMobileUnit_Id(unit.getId());

        List<MobileUnitCoverageRuleEntity> entities = new ArrayList<>();
        for (CoverageRuleRequest rule : rules) {
            entities.add(MobileUnitCoverageRuleEntity.builder()
                    .mobileUnit(unit)
                    .serviceArea(serviceAreas.get(rule.getServiceAreaId()))
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
     * Replaces all coverage rules from map payload. A value of the wrong type (an id that is not a
     * UUID, a date that is not ISO-8601, a number that is not numeric) is a 400 naming that field,
     * rather than being read as absent.
     *
     * @param id          mobile unit identifier
     * @param rulePayload rules from request
     * @return persisted rule responses
     */
    @Transactional
    public List<CoverageRuleResponse> replaceCoverageRules(String id, List<Map<String, Object>> rulePayload) {
        UUID unitId = parseUuidField(id, "id");
        if (unitId == null) {
            throw InvalidFieldException.invalid("id", "id is required");
        }
        List<CoverageRuleRequest> typedRules = new ArrayList<>();
        if (rulePayload != null) {
            for (int i = 0; i < rulePayload.size(); i++) {
                typedRules.add(toCoverageRuleRequest(rulePayload.get(i), FIELD_RULES + "[" + i + "]."));
            }
        }
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
        Object rawCoverage = request.getOrDefault(FIELD_COVERAGE_RULES, Collections.emptyList());
        if (!(rawCoverage instanceof List<?> coverage)) {
            throw InvalidFieldException.invalid(FIELD_COVERAGE_RULES, "coverageRules must be an array");
        }
        List<CoverageRuleRequest> coverageRules = new ArrayList<>(coverage.size());
        for (int i = 0; i < coverage.size(); i++) {
            String prefix = FIELD_COVERAGE_RULES + "[" + i + "].";
            if (!(coverage.get(i) instanceof Map<?, ?> rule)) {
                throw InvalidFieldException.invalid(
                        FIELD_COVERAGE_RULES + "[" + i + "]", "coverage rule must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedRule = (Map<String, Object>) rule;
            coverageRules.add(toCoverageRuleRequest(typedRule, prefix));
        }
        Object name = request.get(FIELD_NAME);
        Object notes = request.get(PATCH_KEY_NOTES);
        return MobileUnitRequest.builder()
                .name(name == null ? null : String.valueOf(name))
                .baseLocationId(parseUuidField(request.get(FIELD_BASE_LOCATION_ID), FIELD_BASE_LOCATION_ID))
                .status(request.get(PATCH_KEY_STATUS) == null ? null : String.valueOf(request.get(PATCH_KEY_STATUS)))
                .travelBufferPolicyId(parseUuidField(
                        request.get(PATCH_KEY_TRAVEL_BUFFER_POLICY_ID), PATCH_KEY_TRAVEL_BUFFER_POLICY_ID))
                .notes(notes == null ? null : String.valueOf(notes))
                .serviceCapabilityCodes(stringList(request.get(PATCH_KEY_SERVICE_CAPABILITY_CODES)))
                .coverageRules(coverageRules)
                .build();
    }

    private CoverageRuleRequest toCoverageRuleRequest(Map<String, Object> source, String prefix) {
        if (source == null) {
            throw InvalidFieldException.invalid(
                    prefix.substring(0, prefix.length() - 1), "coverage rule must be an object");
        }
        return CoverageRuleRequest.builder()
                .serviceAreaId(parseUuidField(source.get("serviceAreaId"), prefix + "serviceAreaId"))
                .ruleType(source.get("ruleType") == null ? null : String.valueOf(source.get("ruleType")))
                .priority(parseIntegerField(source.get("priority"), prefix + "priority"))
                .validFrom(parseLocalDateField(source.get("validFrom"), prefix + "validFrom"))
                .validTo(parseLocalDateField(source.get("validTo"), prefix + "validTo"))
                .maxDistance(parseBigDecimalField(source.get(FIELD_MAX_DISTANCE), prefix + FIELD_MAX_DISTANCE))
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

    /** Create's status: omitted or blank defaults to INACTIVE; anything else must be ACTIVE or INACTIVE. */
    private static String normalizeCreateStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_INACTIVE;
        }
        return requireStatus(status);
    }

    /** A status a client explicitly sent: ACTIVE or INACTIVE in any case, else 400 — never stored as-is. */
    private static String requireStatus(Object status) {
        if (status instanceof String text) {
            String normalized = text.trim().toUpperCase(Locale.ROOT);
            if (STATUSES.contains(normalized)) {
                return normalized;
            }
        }
        throw InvalidFieldException.invalid(PATCH_KEY_STATUS, STATUS_INVALID);
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

    private static Integer parseIntegerField(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer number) {
            return number;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw InvalidFieldException.invalid(field, field + " must be a whole number");
        }
    }

    private static BigDecimal parseBigDecimalField(Object value, String field) {
        if (value == null) {
            return null;
        }
        BigDecimal parsed = parseBigDecimal(value);
        if (parsed == null) {
            throw InvalidFieldException.invalid(field, field + " must be a number");
        }
        return parsed;
    }

    private static BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static LocalDate parseLocalDateField(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (DateTimeParseException exception) {
            throw InvalidFieldException.invalid(field, field + " must be an ISO-8601 date (yyyy-MM-dd)");
        }
    }

    /** {@code null} stays {@code null}; anything else must be a UUID, else 400 naming the field. */
    private static UUID parseUuidField(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (IllegalArgumentException exception) {
            throw InvalidFieldException.invalid(field, field + " must be a UUID");
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
