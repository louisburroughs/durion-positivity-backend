package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ServicePackageMemberRequestDto;
import com.positivity.catalog.internal.dto.ServicePackageMemberResponseDto;
import com.positivity.catalog.internal.dto.ServicePackageRequestDto;
import com.positivity.catalog.internal.dto.ServicePackageResponseDto;
import com.positivity.catalog.internal.entity.ServiceEntity;
import com.positivity.catalog.internal.entity.ServicePackageEntity;
import com.positivity.catalog.internal.entity.ServicePackageMemberEntity;
import com.positivity.catalog.internal.enums.LaborStandardOwnerScope;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ServicePackageMemberRepository;
import com.positivity.catalog.internal.repository.ServicePackageRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service packages and fleet requirement sets (#1575 Tier 0, T0-4).
 *
 * <p>Membership is validated against the real service catalog on every write: a package naming a
 * service that does not exist is a package that cannot be quoted, and finding that out at quote
 * time rather than authoring time makes it the customer's problem instead of the author's.
 *
 * <p>Listings read members in one batch rather than per package. A package listing is a
 * counter-facing read and an N+1 there is a visible stall, not a background cost.
 */
@Service
@RequiredArgsConstructor
public class ServicePackageServiceImpl implements ServicePackageService {

    private static final int SEQUENCE_STEP = 10;

    private final ServicePackageRepository packageRepository;
    private final ServicePackageMemberRepository memberRepository;
    private final ServiceRepository serviceRepository;

    @Override
    @NonNull
    @Transactional
    public ServicePackageResponseDto create(@NonNull ServicePackageRequestDto request) {
        String packageCode = LaborTimeValidation.validatedOperationCodeShape(request.getPackageCode(), "packageCode");
        if (packageCode == null) {
            throw new CatalogValidationException("packageCode is required");
        }
        packageRepository.findByPackageCode(packageCode).ifPresent(existing -> {
            throw new CatalogBusinessRuleException(
                    "A service package already exists with code " + packageCode + " (" + existing.getId() + ")");
        });

        ServicePackageEntity entity = new ServicePackageEntity();
        entity.setPackageCode(packageCode);
        entity.setName(requiredText(request.getName(), "name"));
        entity.setDescription(trimToNull(request.getDescription()));
        applyOwnership(entity, request);
        entity.setFleetPartyId(request.getFleetPartyId());
        entity.setPackageLaborHours(
                LaborTimeValidation.validatedTenthsHours(request.getPackageLaborHours(), "packageLaborHours"));
        entity.setActive(request.getActive() == null || request.getActive());
        entity.setEffectiveFrom(request.getEffectiveFrom());
        entity.setEffectiveTo(validatedWindow(request));
        return toResponse(packageRepository.save(entity), List.of());
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public ServicePackageResponseDto get(@NonNull UUID packageId) {
        ServicePackageEntity entity = requirePackage(packageId);
        return toResponse(entity, memberRepository.findByPackageIdOrderBySequenceAsc(packageId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<ServicePackageResponseDto> list(
            @Nullable UUID locationId, @Nullable UUID fleetPartyId, boolean includeFleetPackages) {
        // Asking for one fleet's packages is itself the decision to include them; requiring the
        // caller to set both flags would only ever produce an empty list by accident.
        List<ServicePackageEntity> packages =
                packageRepository.findSellable(locationId, fleetPartyId, includeFleetPackages || fleetPartyId != null);
        if (packages.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ServicePackageMemberEntity>> membersByPackage = new LinkedHashMap<>();
        for (ServicePackageMemberEntity member : memberRepository.findByPackageIdInOrderBySequenceAsc(
                packages.stream().map(ServicePackageEntity::getId).toList())) {
            membersByPackage
                    .computeIfAbsent(member.getPackageId(), id -> new ArrayList<>())
                    .add(member);
        }
        return packages.stream()
                .map(entity -> toResponse(entity, membersByPackage.getOrDefault(entity.getId(), List.of())))
                .toList();
    }

    @Override
    @NonNull
    @Transactional
    public ServicePackageResponseDto addMember(
            @NonNull UUID packageId, @NonNull ServicePackageMemberRequestDto request) {
        requirePackage(packageId);
        UUID serviceId = request.getServiceId();
        if (serviceId == null) {
            throw new CatalogValidationException("serviceId is required");
        }
        if (!serviceRepository.existsById(serviceId)) {
            throw new CatalogNotFoundException("Service not found: " + serviceId);
        }
        if (memberRepository.existsByPackageIdAndServiceId(packageId, serviceId)) {
            throw new CatalogBusinessRuleException("Service " + serviceId
                    + " is already a member of this package; change its quantity rather than adding it twice");
        }

        List<ServicePackageMemberEntity> existing = memberRepository.findByPackageIdOrderBySequenceAsc(packageId);
        ServicePackageMemberEntity member = new ServicePackageMemberEntity();
        member.setPackageId(packageId);
        member.setServiceId(serviceId);
        member.setSequence(request.getSequence() == null ? nextSequence(existing) : request.getSequence());
        member.setQuantity(positiveQuantity(request.getQuantity()));
        member.setRequired(request.getRequired() == null || request.getRequired());
        memberRepository.save(member);

        return get(packageId);
    }

    @Override
    @NonNull
    @Transactional
    public ServicePackageResponseDto removeMember(@NonNull UUID packageId, @NonNull UUID memberId) {
        requirePackage(packageId);
        ServicePackageMemberEntity member = memberRepository
                .findById(memberId)
                .filter(row -> packageId.equals(row.getPackageId()))
                .orElseThrow(() ->
                        new CatalogNotFoundException("Member " + memberId + " not found in package " + packageId));
        memberRepository.delete(member);
        return get(packageId);
    }

    // ── Validation ──────────────────────────────────────────────────────────────────────

    private ServicePackageEntity requirePackage(UUID packageId) {
        return packageRepository
                .findById(packageId)
                .orElseThrow(() -> new CatalogNotFoundException("Service package not found: " + packageId));
    }

    /** Same paired rule and rationale as the labor standard's (V21/V23 CHECK); 422, not 500. */
    private static void applyOwnership(ServicePackageEntity entity, ServicePackageRequestDto request) {
        LaborStandardOwnerScope scope = parsedOwnerScope(request.getOwnerScope());
        if (scope == LaborStandardOwnerScope.SHOP && request.getOwnerLocationId() == null) {
            throw new CatalogValidationException("ownerLocationId is required when ownerScope is SHOP");
        }
        if (scope == LaborStandardOwnerScope.PLATFORM && request.getOwnerLocationId() != null) {
            throw new CatalogValidationException(
                    "ownerLocationId must be omitted when ownerScope is PLATFORM; a platform package has no owning location");
        }
        entity.setOwnerScope(scope);
        entity.setOwnerLocationId(request.getOwnerLocationId());
    }

    private static LaborStandardOwnerScope parsedOwnerScope(@Nullable String ownerScope) {
        if (ownerScope == null || ownerScope.isBlank()) {
            return LaborStandardOwnerScope.PLATFORM;
        }
        try {
            return LaborStandardOwnerScope.valueOf(ownerScope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CatalogValidationException("ownerScope must be one of "
                    + Arrays.toString(LaborStandardOwnerScope.values()) + ": " + ownerScope);
        }
    }

    @Nullable
    private static LocalDate validatedWindow(ServicePackageRequestDto request) {
        if (request.getEffectiveFrom() != null
                && request.getEffectiveTo() != null
                && request.getEffectiveTo().isBefore(request.getEffectiveFrom())) {
            throw new CatalogValidationException("effectiveTo must not be before effectiveFrom");
        }
        return request.getEffectiveTo();
    }

    private static BigDecimal positiveQuantity(@Nullable BigDecimal quantity) {
        if (quantity == null) {
            return BigDecimal.ONE;
        }
        if (quantity.signum() <= 0) {
            throw new CatalogValidationException("quantity must be greater than zero: " + quantity);
        }
        return quantity;
    }

    private static int nextSequence(List<ServicePackageMemberEntity> existing) {
        return existing.stream()
                        .max(Comparator.comparingInt(ServicePackageMemberEntity::getSequence))
                        .map(ServicePackageMemberEntity::getSequence)
                        .orElse(0)
                + SEQUENCE_STEP;
    }

    private static String requiredText(@Nullable String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new CatalogValidationException(field + " is required");
        }
        return trimmed;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ── Mapping ─────────────────────────────────────────────────────────────────────────

    private ServicePackageResponseDto toResponse(
            ServicePackageEntity entity, List<ServicePackageMemberEntity> members) {
        ServicePackageResponseDto dto = new ServicePackageResponseDto();
        dto.setId(entity.getId());
        dto.setPackageCode(entity.getPackageCode());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setOwnerScope(entity.getOwnerScope().name());
        dto.setOwnerLocationId(entity.getOwnerLocationId());
        dto.setFleetPartyId(entity.getFleetPartyId());
        dto.setPackageLaborHours(entity.getPackageLaborHours());
        dto.setActive(entity.isActive());
        dto.setEffectiveFrom(entity.getEffectiveFrom());
        dto.setEffectiveTo(entity.getEffectiveTo());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setMembers(toMemberResponses(members));
        return dto;
    }

    private List<ServicePackageMemberResponseDto> toMemberResponses(List<ServicePackageMemberEntity> members) {
        if (members.isEmpty()) {
            return List.of();
        }
        Map<UUID, ServiceEntity> services = new LinkedHashMap<>();
        for (ServiceEntity service : serviceRepository.findAllById(members.stream()
                .map(ServicePackageMemberEntity::getServiceId)
                .distinct()
                .toList())) {
            services.put(service.getId(), service);
        }
        return members.stream()
                .map(member -> {
                    ServicePackageMemberResponseDto dto = new ServicePackageMemberResponseDto();
                    dto.setId(member.getId());
                    dto.setServiceId(member.getServiceId());
                    dto.setSequence(member.getSequence());
                    dto.setQuantity(member.getQuantity());
                    dto.setRequired(member.isRequired());
                    ServiceEntity service = services.get(member.getServiceId());
                    if (service != null) {
                        dto.setOperationCode(service.getOperationCode());
                        dto.setServiceName(service.getName());
                    }
                    return dto;
                })
                .toList();
    }
}
