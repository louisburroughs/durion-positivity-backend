package com.positivity.location.internal.service;

import com.positivity.location.service.MobileUnitService;

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
import com.positivity.location.internal.repository.MobileUnitCoverageRuleRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import com.positivity.location.internal.repository.ServiceLocationCapabilityRepository;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import java.math.BigDecimal;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API for mobile unit management and eligibility evaluation.
 *
 * Issue: #76
 */
@Service
public class MobileUnitServiceImpl implements MobileUnitService {
    private static final String MOBILE_UNIT_NAME_TAKEN = "MOBILE_UNIT_NAME_TAKEN";
    private static final String MOBILE_UNIT_CONFLICT = "MOBILE_UNIT_CONFLICT";

    protected final MobileUnitRepository mobileUnitRepository;
    protected final MobileUnitCoverageRuleRepository coverageRuleRepository;
    protected final ServiceAreaRepository serviceAreaRepository;
    protected final TravelBufferPolicyRepository travelBufferPolicyRepository;
    protected final ServiceLocationCapabilityRepository serviceLocationCapabilityRepository;

    public MobileUnitServiceImpl(
            MobileUnitRepository mobileUnitRepository,
            MobileUnitCoverageRuleRepository coverageRuleRepository,
            ServiceAreaRepository serviceAreaRepository,
            TravelBufferPolicyRepository travelBufferPolicyRepository,
            ServiceLocationCapabilityRepository serviceLocationCapabilityRepository) {
        this.mobileUnitRepository = mobileUnitRepository;
        this.coverageRuleRepository = coverageRuleRepository;
        this.serviceAreaRepository = serviceAreaRepository;
        this.travelBufferPolicyRepository = travelBufferPolicyRepository;
        this.serviceLocationCapabilityRepository = serviceLocationCapabilityRepository;
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
        return createMobileUnit(typedRequest);
    }

    /**
     * Creates a mobile unit using a typed request DTO.
     *
     * @param request mobile unit payload
     * @return created mobile unit response
     */
    @Transactional
    public MobileUnitResponse createMobileUnit(MobileUnitRequest request) {
        // Issue #76: ACTIVE mobile units require policy, capabilities, and coverage
        // rules.
        String normalizedStatus = normalizeStatus(request.getStatus());
        List<String> capabilityIds = request.getCapabilityIds() == null ? List.of() : request.getCapabilityIds();
        List<CoverageRuleRequest> coverageRules = request.getCoverageRules() == null ? List.of()
                : request.getCoverageRules();

        if ("ACTIVE".equals(normalizedStatus)) {
            if (request.getTravelBufferPolicyId() == null || capabilityIds.isEmpty() || coverageRules.isEmpty()) {
                throw new IllegalArgumentException(
                        "ACTIVE mobile unit requires travelBufferPolicyId, capabilityIds, and coverageRules");
            }
        }

        if (request.getTravelBufferPolicyId() != null && travelBufferPolicyRepository != null
                && travelBufferPolicyRepository.findById(request.getTravelBufferPolicyId()).isEmpty()) {
            throw new IllegalArgumentException("Unknown travelBufferPolicyId: " + request.getTravelBufferPolicyId());
        }

        Set<UUID> resolvedCapabilityIds = resolveCapabilityIds(capabilityIds);

        if (coverageRules.stream().anyMatch(rule -> "DISTANCE_TIER".equalsIgnoreCase(rule.getRuleType()))) {
            validateDistanceTiers(coverageRules.stream().map(this::toTierMap).toList());
        }

        UUID baseLocationId = request.getBaseLocationId();
        String unitName = request.getName() == null ? "" : request.getName().trim();
        if (baseLocationId != null && !unitName.isEmpty()) {
            boolean repositoryDuplicate = mobileUnitRepository != null
                    && mobileUnitRepository.existsByBaseLocationIdAndNameIgnoreCase(baseLocationId, unitName);
            if (repositoryDuplicate) {
                throw new DuplicateResourceException(MOBILE_UNIT_NAME_TAKEN);
            }
        }

        MobileUnitEntity entity = MobileUnitEntity.builder()
                .name(request.getName())
                .baseLocationId(request.getBaseLocationId())
                .status(normalizedStatus)
                .travelBufferPolicyId(request.getTravelBufferPolicyId())
                .notes(request.getNotes())
                .capabilityIds(resolvedCapabilityIds)
                .build();

        MobileUnitEntity persisted = entity;
        if (mobileUnitRepository != null) {
            try {
                MobileUnitEntity saved = mobileUnitRepository.save(entity);
                if (saved != null) {
                    persisted = saved;
                }
            } catch (DataIntegrityViolationException exception) {
                throw toMobileUnitConflictException(exception);
            }
        }

        if (!coverageRules.isEmpty()) {
            replaceCoverageRules(persisted.getId(), coverageRules);
        }

        return toMobileUnitResponse(persisted);
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
        if (mobileUnitRepository == null) {
            return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
        }
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
        if (mobileUnitRepository == null) {
            return java.util.Optional.empty();
        }
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
        MobileUnitEntity entity = mobileUnitRepository == null ? null : mobileUnitRepository.findById(id).orElse(null);
        if (entity == null) {
            return MobileUnitResponse.builder()
                    .id(id)
                    .name((String) patch.get("name"))
                    .status(patch.containsKey("status") ? normalizeStatus(String.valueOf(patch.get("status")))
                            : "INACTIVE")
                    .travelBufferPolicyId(parseUuid(patch.get("travelBufferPolicyId")))
                    .notes((String) patch.get("notes"))
                    .updatedAt(Instant.now())
                    .build();
        }

        if (patch.containsKey("name")) {
            entity.setName((String) patch.get("name"));
        }
        if (patch.containsKey("status")) {
            entity.setStatus(normalizeStatus(String.valueOf(patch.get("status"))));
        }
        if (patch.containsKey("notes")) {
            entity.setNotes((String) patch.get("notes"));
        }
        if (patch.containsKey("travelBufferPolicyId")) {
            entity.setTravelBufferPolicyId(parseUuid(patch.get("travelBufferPolicyId")));
        }
        entity.setUpdatedAt(Instant.now());

        MobileUnitEntity saved = entity;
        if (mobileUnitRepository != null) {
            try {
                saved = mobileUnitRepository.save(entity);
            } catch (DataIntegrityViolationException exception) {
                throw toMobileUnitConflictException(exception);
            }
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
        if (coverageRuleRepository == null) {
            return List.of();
        }
        MobileUnitEntity unit = mobileUnitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mobile unit not found"));

        coverageRuleRepository.deleteByMobileUnit_Id(id);

        List<MobileUnitCoverageRuleEntity> entities = new ArrayList<>();
        for (CoverageRuleRequest rule : rules) {
            ServiceAreaEntity serviceArea = null;
            if (rule.getServiceAreaId() != null && serviceAreaRepository != null) {
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
        return replaceCoverageRules(unitId, typedRules);
    }

    /**
     * Returns coverage rules for a mobile unit.
     *
     * @param id mobile unit identifier
     * @return ordered coverage rules
     */
    @Transactional(readOnly = true)
    public List<CoverageRuleResponse> getCoverageRules(UUID id) {
        if (coverageRuleRepository == null) {
            return List.of();
        }
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
        if (coverageRuleRepository == null) {
            return List.of();
        }
        LocalDate atDate = at == null ? LocalDate.now(ZoneOffset.UTC) : at.atZone(ZoneOffset.UTC).toLocalDate();
        List<MobileUnitCoverageRuleEntity> rules = coverageRuleRepository.findEligibleCoverageRules(postalCode,
                countryCode,
                atDate);

        Map<UUID, EligibleMobileUnitResponse> ordered = new LinkedHashMap<>();
        for (MobileUnitCoverageRuleEntity rule : rules) {
            MobileUnitEntity unit = rule.getMobileUnit();
            if (unit == null || !"ACTIVE".equalsIgnoreCase(unit.getStatus())) {
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
                .status(request.get("status") == null ? null : String.valueOf(request.get("status")))
                .travelBufferPolicyId(parseUuid(request.get("travelBufferPolicyId")))
                .notes((String) request.get("notes"))
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
                .maxDistance(parseBigDecimal(source.get("maxDistance")))
                .build();
    }

    private Map<String, Object> toTierMap(CoverageRuleRequest request) {
        Map<String, Object> map = new HashMap<>();
        map.put("maxDistance", request.getMaxDistance());
        return map;
    }

    private Set<UUID> resolveCapabilityIds(List<String> capabilityIds) {
        if (capabilityIds == null || capabilityIds.isEmpty()) {
            return Set.of();
        }

        List<String> normalized = capabilityIds.stream()
                .map(value -> value == null ? "" : value.trim())
                .toList();

        Set<String> invalidValues = new LinkedHashSet<>();
        Set<UUID> requestedIds = new LinkedHashSet<>();
        Map<String, UUID> requestedCodes = new LinkedHashMap<>();
        for (String value : normalized) {
            if (value.isBlank()) {
                invalidValues.add("<blank>");
                continue;
            }
            UUID parsedId = parseUuid(value);
            if (parsedId != null) {
                requestedIds.add(parsedId);
            } else {
                requestedCodes.put(value.toUpperCase(Locale.ROOT), null);
            }
        }

        if (serviceLocationCapabilityRepository == null) {
            if (!invalidValues.isEmpty() || !requestedCodes.isEmpty()) {
                Set<String> fallbackInvalid = new LinkedHashSet<>(invalidValues);
                fallbackInvalid.addAll(requestedCodes.keySet());
                throw new IllegalArgumentException("Invalid capabilityIds: " + String.join(", ", fallbackInvalid));
            }
            return requestedIds;
        }

        Set<UUID> resolvedIds = new LinkedHashSet<>();
        if (!requestedIds.isEmpty()) {
            Set<UUID> foundIds = new LinkedHashSet<>();
            for (ServiceLocationCapabilityEntity capability : serviceLocationCapabilityRepository.findAllById(requestedIds)) {
                foundIds.add(capability.getId());
            }
            for (UUID requestedId : requestedIds) {
                if (foundIds.contains(requestedId)) {
                    resolvedIds.add(requestedId);
                } else {
                    invalidValues.add(requestedId.toString());
                }
            }
        }

        if (!requestedCodes.isEmpty()) {
            List<String> requestedCodeKeys = new ArrayList<>(requestedCodes.keySet());
            for (ServiceLocationCapabilityEntity capability : serviceLocationCapabilityRepository
                    .findByCodeIn(requestedCodeKeys)) {
                if (capability.getCode() != null) {
                    requestedCodes.put(capability.getCode().toUpperCase(Locale.ROOT), capability.getId());
                }
            }
            for (Map.Entry<String, UUID> entry : requestedCodes.entrySet()) {
                if (entry.getValue() == null) {
                    invalidValues.add(entry.getKey());
                } else {
                    resolvedIds.add(entry.getValue());
                }
            }
        }

        if (!invalidValues.isEmpty()) {
            throw new IllegalArgumentException("Invalid capabilityIds: " + String.join(", ", invalidValues));
        }
        return resolvedIds;
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
            return "INACTIVE";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal extractMaxDistance(Object entry) {
        if (entry == null) {
            return null;
        }
        if (entry instanceof Map<?, ?> map) {
            return parseBigDecimal(map.get("maxDistance"));
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
