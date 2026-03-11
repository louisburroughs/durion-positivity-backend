package com.positivity.location.internal.service;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.location.internal.dto.CoverageRuleRequest;
import com.positivity.location.internal.dto.CoverageRuleResponse;
import com.positivity.location.internal.dto.EligibleMobileUnitResponse;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.entity.MobileUnitCoverageRuleEntity;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.entity.ServiceLocationCapabilityEntity;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.MobileUnitCoverageRuleRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import com.positivity.location.internal.repository.ServiceLocationCapabilityRepository;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import com.positivity.location.service.MobileUnitService;

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
    private static final String FIELD_MAX_DISTANCE = "maxDistance";

    protected final MobileUnitRepository mobileUnitRepository;
    protected final MobileUnitCoverageRuleRepository coverageRuleRepository;
    protected final ServiceAreaRepository serviceAreaRepository;
    protected final TravelBufferPolicyRepository travelBufferPolicyRepository;
    protected final ServiceLocationCapabilityRepository serviceLocationCapabilityRepository;
    protected final LocationRepository locationRepository;

    public MobileUnitServiceImpl(
            MobileUnitRepository mobileUnitRepository,
            MobileUnitCoverageRuleRepository coverageRuleRepository,
            ServiceAreaRepository serviceAreaRepository,
            TravelBufferPolicyRepository travelBufferPolicyRepository,
            ServiceLocationCapabilityRepository serviceLocationCapabilityRepository,
            LocationRepository locationRepository,
            Clock clock) {
        this.clock = clock;
        this.mobileUnitRepository = mobileUnitRepository;
        this.coverageRuleRepository = coverageRuleRepository;
        this.serviceAreaRepository = serviceAreaRepository;
        this.travelBufferPolicyRepository = travelBufferPolicyRepository;
        this.serviceLocationCapabilityRepository = serviceLocationCapabilityRepository;
        this.locationRepository = locationRepository;
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
        List<String> capabilityIds = nonNullList(request.getCapabilityIds());
        List<CoverageRuleRequest> coverageRules = nonNullList(request.getCoverageRules());

        validateCreateMobileUnitRequest(
                normalizedStatus,
                request.getTravelBufferPolicyId(),
                capabilityIds,
                coverageRules);

        Set<UUID> resolvedCapabilityIds = resolveCapabilityIds(capabilityIds);
        validateDistanceTierRules(coverageRules);
        validateUnitNameUniqueness(request.getBaseLocationId(), request.getName());

        MobileUnitEntity persisted = persistMobileUnitEntity(request, normalizedStatus, resolvedCapabilityIds);
        if (!coverageRules.isEmpty()) {
            replaceCoverageRulesInternal(persisted.getId(), coverageRules);
        }
        return toMobileUnitResponse(persisted);
    }

    private <T> List<T> nonNullList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void validateCreateMobileUnitRequest(
            String normalizedStatus,
            UUID travelBufferPolicyId,
            List<String> capabilityIds,
            List<CoverageRuleRequest> coverageRules) {
        if (STATUS_ACTIVE.equals(normalizedStatus)
                && (travelBufferPolicyId == null || capabilityIds.isEmpty() || coverageRules.isEmpty())) {
            throw new IllegalArgumentException(
                    "ACTIVE mobile unit requires travelBufferPolicyId, capabilityIds, and coverageRules");
        }

        if (travelBufferPolicyId != null && travelBufferPolicyRepository.findById(travelBufferPolicyId).isEmpty()) {
            throw new IllegalArgumentException("Unknown travelBufferPolicyId: " + travelBufferPolicyId);
        }
    }

    private void validateDistanceTierRules(List<CoverageRuleRequest> coverageRules) {
        boolean hasDistanceTierRules = coverageRules.stream()
                .anyMatch(rule -> "DISTANCE_TIER".equalsIgnoreCase(rule.getRuleType()));
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
            MobileUnitRequest request,
            String normalizedStatus,
            Set<UUID> resolvedCapabilityIds) {
        UUID baseLocationId = request.getBaseLocationId();
        MobileUnitEntity entity = MobileUnitEntity.builder()
                .name(request.getName())
                .baseLocation(baseLocationId != null
                        ? locationRepository.findById(baseLocationId).orElse(null)
                        : null)
                .status(normalizedStatus)
                .travelBufferPolicyId(request.getTravelBufferPolicyId())
                .notes(request.getNotes())
                .capabilityIds(resolvedCapabilityIds)
                .build();

        try {
            return mobileUnitRepository.save(entity);
        } catch (DataIntegrityViolationException exception) {
            throw toMobileUnitConflictException(exception);
        }
    }

    /**
     * Validates capability IDs against story #76 rules.
     *
     * @param capabilityIds capability IDs from request payload
     */
    public void validateCapabilityIds(List<?> capabilityIds) {
        if (capabilityIds == null) {
            return;
        }
        resolveCapabilityIds(capabilityIds.stream().map(String::valueOf).toList());
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
                    .status(patch.containsKey(PATCH_KEY_STATUS)
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
        entity.setUpdatedAt(Instant.now(clock));

        MobileUnitEntity saved = entity;
        try {
            saved = mobileUnitRepository.save(entity);
        } catch (DataIntegrityViolationException exception) {
            throw toMobileUnitConflictException(exception);
        }
        return toMobileUnitResponse(saved);
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
        MobileUnitEntity unit = mobileUnitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mobile unit not found"));

        coverageRuleRepository.deleteByMobileUnit_Id(id);

        List<MobileUnitCoverageRuleEntity> entities = new ArrayList<>();
        for (CoverageRuleRequest rule : rules) {
            ServiceAreaEntity serviceArea = null;
            if (rule.getServiceAreaId() != null) {
                serviceArea = serviceAreaRepository.findById(rule.getServiceAreaId()).orElse(null);
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
        List<CoverageRuleRequest> typedRules = rulePayload == null ? List.of()
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
        LocalDate atDate = at == null ? LocalDate.now(ZoneOffset.UTC) : at.atZone(ZoneOffset.UTC).toLocalDate();
        List<MobileUnitCoverageRuleEntity> rules = coverageRuleRepository.findEligibleCoverageRules(postalCode,
                countryCode,
                atDate);

        Map<UUID, EligibleMobileUnitResponse> ordered = new LinkedHashMap<>();
        for (MobileUnitCoverageRuleEntity rule : rules) {
            MobileUnitEntity unit = rule.getMobileUnit();
            if (unit == null || !STATUS_ACTIVE.equalsIgnoreCase(unit.getStatus())) {
                continue;
            }
            ordered.putIfAbsent(unit.getId(), EligibleMobileUnitResponse.builder()
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
        List<Map<String, Object>> rawCoverage = (List<Map<String, Object>>) request.getOrDefault("coverageRules",
                Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Object> rawCapabilityIds = (List<Object>) request.getOrDefault("capabilityIds", Collections.emptyList());
        return MobileUnitRequest.builder()
                .name((String) request.get("name"))
                .baseLocationId(parseUuid(request.get("baseLocationId")))
                .status(request.get(PATCH_KEY_STATUS) == null ? null : String.valueOf(request.get(PATCH_KEY_STATUS)))
                .travelBufferPolicyId(parseUuid(request.get(PATCH_KEY_TRAVEL_BUFFER_POLICY_ID)))
                .notes((String) request.get(PATCH_KEY_NOTES))
                .capabilityIds(rawCapabilityIds.stream().map(String::valueOf).toList())
                .coverageRules(rawCoverage.stream().map(this::toCoverageRuleRequest).toList())
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

    private Set<UUID> resolveCapabilityIds(List<String> capabilityIds) {
        if (capabilityIds == null || capabilityIds.isEmpty()) {
            return Set.of();
        }

        CapabilityResolutionContext context = buildCapabilityResolutionContext(capabilityIds);
        Set<UUID> resolvedIds = new LinkedHashSet<>();
        resolvedIds.addAll(resolveCapabilityIdsByUuid(context.requestedIds, context.invalidValues));
        resolvedIds.addAll(resolveCapabilityIdsByCode(context.requestedCodes, context.invalidValues));
        assertNoInvalidCapabilityValues(context.invalidValues);
        return resolvedIds;
    }

    private CapabilityResolutionContext buildCapabilityResolutionContext(List<String> capabilityIds) {
        CapabilityResolutionContext context = new CapabilityResolutionContext();
        List<String> normalizedValues = capabilityIds.stream()
                .map(value -> value == null ? "" : value.trim())
                .toList();
        for (String value : normalizedValues) {
            if (value.isBlank()) {
                context.invalidValues.add("<blank>");
                continue;
            }
            UUID parsedId = parseUuid(value);
            if (parsedId != null) {
                context.requestedIds.add(parsedId);
            } else {
                context.requestedCodes.put(value.toUpperCase(Locale.ROOT), null);
            }
        }
        return context;
    }

    private Set<UUID> resolveCapabilityIdsByUuid(Set<UUID> requestedIds, Set<String> invalidValues) {
        if (requestedIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> foundIds = new LinkedHashSet<>();
        for (ServiceLocationCapabilityEntity capability : serviceLocationCapabilityRepository
                .findAllById(requestedIds)) {
            foundIds.add(capability.getId());
        }
        Set<UUID> resolvedIds = new LinkedHashSet<>();
        for (UUID requestedId : requestedIds) {
            if (foundIds.contains(requestedId)) {
                resolvedIds.add(requestedId);
            } else {
                invalidValues.add(requestedId.toString());
            }
        }
        return resolvedIds;
    }

    private Set<UUID> resolveCapabilityIdsByCode(Map<String, UUID> requestedCodes, Set<String> invalidValues) {
        if (requestedCodes.isEmpty()) {
            return Set.of();
        }
        List<String> requestedCodeKeys = new ArrayList<>(requestedCodes.keySet());
        for (ServiceLocationCapabilityEntity capability : serviceLocationCapabilityRepository
                .findByCodeIn(requestedCodeKeys)) {
            if (capability.getCode() != null) {
                requestedCodes.put(capability.getCode().toUpperCase(Locale.ROOT), capability.getId());
            }
        }
        Set<UUID> resolvedIds = new LinkedHashSet<>();
        for (Map.Entry<String, UUID> entry : requestedCodes.entrySet()) {
            if (entry.getValue() == null) {
                invalidValues.add(entry.getKey());
            } else {
                resolvedIds.add(entry.getValue());
            }
        }
        return resolvedIds;
    }

    private void assertNoInvalidCapabilityValues(Set<String> invalidValues) {
        if (!invalidValues.isEmpty()) {
            throw new IllegalArgumentException("Invalid capabilityIds: " + String.join(", ", invalidValues));
        }
    }

    private MobileUnitResponse toMobileUnitResponse(MobileUnitEntity entity) {
        return MobileUnitResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .baseLocationId(entity.getBaseLocationId())
                .status(entity.getStatus())
                .travelBufferPolicyId(entity.getTravelBufferPolicyId())
                .notes(entity.getNotes())
                .capabilityIds(entity.getCapabilityIds() == null ? List.of()
                        : entity.getCapabilityIds().stream().map(UUID::toString).toList())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private CoverageRuleResponse toCoverageRuleResponse(MobileUnitCoverageRuleEntity entity) {
        return CoverageRuleResponse.builder()
                .id(entity.getId())
                .mobileUnitId(entity.getMobileUnit() == null ? null : entity.getMobileUnit().getId())
                .serviceAreaId(entity.getServiceArea() == null ? null : entity.getServiceArea().getId())
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

    private static final class CapabilityResolutionContext {
        private final Set<String> invalidValues = new LinkedHashSet<>();
        private final Set<UUID> requestedIds = new LinkedHashSet<>();
        private final Map<String, UUID> requestedCodes = new LinkedHashMap<>();
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
