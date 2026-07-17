package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.PolicyRequest;
import com.positivity.warranty.internal.dto.PolicyResponse;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import com.positivity.warranty.internal.repository.WarrantyProviderRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PolicyServiceImpl implements PolicyService {

    static final String NOT_FOUND_CODE = "WARRANTY_POLICY_NOT_FOUND";

    private final WarrantyPolicyRepository policyRepository;
    private final WarrantyProviderRepository providerRepository;

    @Override
    @NonNull
    @Transactional
    public PolicyResponse create(@NonNull PolicyRequest request) {
        requireProviderExists(request.providerId());
        validateAppliesTo(request);
        WarrantyPolicy policy = new WarrantyPolicy();
        apply(policy, request);
        return toResponse(policyRepository.save(policy));
    }

    @Override
    @NonNull
    @Transactional
    public PolicyResponse update(@NonNull UUID id, @NonNull PolicyRequest request) {
        WarrantyPolicy policy = load(id);
        requireProviderExists(request.providerId());
        validateAppliesTo(request);
        apply(policy, request);
        return toResponse(policyRepository.save(policy));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PolicyResponse getById(@NonNull UUID id) {
        return toResponse(load(id));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<PolicyResponse> list(@Nullable UUID providerId, @Nullable CoverageType coverageType) {
        List<WarrantyPolicy> policies;
        if (providerId != null) {
            policies = policyRepository.findByProviderId(providerId);
        } else if (coverageType != null) {
            policies = policyRepository.findByCoverageType(coverageType);
        } else {
            policies = policyRepository.findAll();
        }
        return policies.stream()
                .filter(p -> coverageType == null || p.getCoverageType() == coverageType)
                .map(PolicyServiceImpl::toResponse)
                .toList();
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<PolicyResponse> findApplicable(
            @Nullable UUID productEntityId,
            @Nullable UUID manufacturerId,
            @Nullable UUID categoryId,
            @NonNull LocalDate saleDate,
            @Nullable CoverageType coverageType) {
        return policyRepository.findEffectiveOn(saleDate).stream()
                .filter(p -> coverageType == null || p.getCoverageType() == coverageType)
                .filter(p -> matchesAppliesTo(p, productEntityId, manufacturerId, categoryId))
                .sorted(Comparator.comparingInt((WarrantyPolicy p) -> specificityRank(p.getAppliesToType()))
                        .thenComparing(WarrantyPolicy::getEffectiveFrom, Comparator.reverseOrder())
                        .thenComparing(WarrantyPolicy::getName))
                .map(PolicyServiceImpl::toResponse)
                .toList();
    }

    /**
     * ALL always matches; scoped policies match only when the corresponding key was provided
     * and equals the policy's scope value (PRD §3.2 appliesTo / §6 step 1).
     */
    private static boolean matchesAppliesTo(
            WarrantyPolicy policy,
            @Nullable UUID productEntityId,
            @Nullable UUID manufacturerId,
            @Nullable UUID categoryId) {
        return switch (policy.getAppliesToType()) {
            case PRODUCT_LIST ->
                productEntityId != null
                        && policy.getAppliesToProductEntityIds() != null
                        && policy.getAppliesToProductEntityIds().contains(productEntityId);
            case MANUFACTURER -> manufacturerId != null && manufacturerId.equals(policy.getAppliesToManufacturerId());
            case CATEGORY -> categoryId != null && categoryId.equals(policy.getAppliesToCategoryId());
            case ALL -> true;
        };
    }

    /** Most-specific-first ordering: PRODUCT_LIST &gt; MANUFACTURER &gt; CATEGORY &gt; ALL. */
    private static int specificityRank(AppliesToType type) {
        return switch (type) {
            case PRODUCT_LIST -> 0;
            case MANUFACTURER -> 1;
            case CATEGORY -> 2;
            case ALL -> 3;
        };
    }

    private void requireProviderExists(@NonNull UUID providerId) {
        if (!providerRepository.existsById(providerId)) {
            throw new WarrantyNotFoundException(
                    ProviderServiceImpl.NOT_FOUND_CODE, "Warranty provider '" + providerId + "' was not found");
        }
    }

    /** Cross-field consistency the bean-validation annotations cannot express. */
    private static void validateAppliesTo(PolicyRequest request) {
        switch (request.appliesToType()) {
            case PRODUCT_LIST -> {
                if (request.appliesToProductEntityIds() == null
                        || request.appliesToProductEntityIds().isEmpty()) {
                    throw new IllegalArgumentException(
                            "appliesToProductEntityIds must be a non-empty list when appliesToType is PRODUCT_LIST");
                }
            }
            case CATEGORY -> {
                if (request.appliesToCategoryId() == null) {
                    throw new IllegalArgumentException(
                            "appliesToCategoryId is required when appliesToType is CATEGORY");
                }
            }
            case MANUFACTURER -> {
                if (request.appliesToManufacturerId() == null) {
                    throw new IllegalArgumentException(
                            "appliesToManufacturerId is required when appliesToType is MANUFACTURER");
                }
            }
            case ALL -> {
                // no scope key required
            }
        }
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new IllegalArgumentException("effectiveTo must not be before effectiveFrom");
        }
    }

    @NonNull
    private WarrantyPolicy load(@NonNull UUID id) {
        return policyRepository
                .findById(id)
                .orElseThrow(() ->
                        new WarrantyNotFoundException(NOT_FOUND_CODE, "Warranty policy '" + id + "' was not found"));
    }

    private static void apply(WarrantyPolicy policy, PolicyRequest request) {
        policy.setProviderId(request.providerId());
        policy.setName(request.name());
        policy.setCoverageType(request.coverageType());
        policy.setAppliesToType(request.appliesToType());
        policy.setAppliesToProductEntityIds(request.appliesToProductEntityIds());
        policy.setAppliesToCategoryId(request.appliesToCategoryId());
        policy.setAppliesToManufacturerId(request.appliesToManufacturerId());
        policy.setEffectiveFrom(request.effectiveFrom());
        policy.setEffectiveTo(request.effectiveTo());
        policy.setDurationMonths(request.durationMonths());
        policy.setMileageLimit(request.mileageLimit());
        policy.setTreadPullPointThirtySeconds(request.treadPullPointThirtySeconds());
        policy.setLaborCovered(request.laborCovered() != null ? request.laborCovered() : Boolean.FALSE);
        policy.setLaborHoursCap(request.laborHoursCap());
        policy.setLaborRateCap(request.laborRateCap());
        policy.setProrationMethod(request.prorationMethod());
        policy.setRequiresPartReturn(
                request.requiresPartReturn() != null ? request.requiresPartReturn() : Boolean.FALSE);
        policy.setRequiresPhotoEvidence(
                request.requiresPhotoEvidence() != null ? request.requiresPhotoEvidence() : Boolean.FALSE);
        policy.setTransferable(request.transferable() != null ? request.transferable() : Boolean.FALSE);
        policy.setAutoRegister(request.autoRegister() != null ? request.autoRegister() : Boolean.FALSE);
        policy.setTermsText(request.termsText());
        policy.setDocumentUrls(request.documentUrls());
    }

    @NonNull
    private static PolicyResponse toResponse(@NonNull WarrantyPolicy policy) {
        return new PolicyResponse(
                policy.getId(),
                policy.getProviderId(),
                policy.getName(),
                policy.getCoverageType(),
                policy.getAppliesToType(),
                policy.getAppliesToProductEntityIds(),
                policy.getAppliesToCategoryId(),
                policy.getAppliesToManufacturerId(),
                policy.getEffectiveFrom(),
                policy.getEffectiveTo(),
                policy.getDurationMonths(),
                policy.getMileageLimit(),
                policy.getTreadPullPointThirtySeconds(),
                policy.getLaborCovered(),
                policy.getLaborHoursCap(),
                policy.getLaborRateCap(),
                policy.getProrationMethod(),
                policy.getRequiresPartReturn(),
                policy.getRequiresPhotoEvidence(),
                policy.getTransferable(),
                policy.getAutoRegister(),
                policy.getTermsText(),
                policy.getDocumentUrls(),
                policy.getCreatedAt(),
                policy.getUpdatedAt(),
                policy.getVersion());
    }
}
