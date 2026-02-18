package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.EffectiveLocationPriceResponseDto;
import com.positivity.catalog.internal.dto.GuardrailPolicyUpsertRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideCreateRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideDecisionRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideResponseDto;
import com.positivity.catalog.internal.entity.ApprovalRequestEntity;
import com.positivity.catalog.internal.entity.ApprovalRequestStatus;
import com.positivity.catalog.internal.entity.GuardrailPolicyEntity;
import com.positivity.catalog.internal.entity.GuardrailPolicyScope;
import com.positivity.catalog.internal.entity.LocationPriceOverrideEntity;
import com.positivity.catalog.internal.entity.PriceOverrideStatus;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ApprovalRequestRepository;
import com.positivity.catalog.internal.repository.GuardrailPolicyRepository;
import com.positivity.catalog.internal.repository.LocationPriceOverrideRepository;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocationPriceOverrideService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final LocationPriceOverrideRepository locationPriceOverrideRepository;
    private final GuardrailPolicyRepository guardrailPolicyRepository;
    private final ApprovalRequestRepository approvalRequestRepository;

    public LocationPriceOverrideService(
            LocationPriceOverrideRepository locationPriceOverrideRepository,
            GuardrailPolicyRepository guardrailPolicyRepository,
            ApprovalRequestRepository approvalRequestRepository) {
        this.locationPriceOverrideRepository = locationPriceOverrideRepository;
        this.guardrailPolicyRepository = guardrailPolicyRepository;
        this.approvalRequestRepository = approvalRequestRepository;
    }

    @Transactional
    public LocationPriceOverrideResponseDto upsertLocationGuardrailPolicy(
            @NonNull GuardrailPolicyUpsertRequestDto request) {
        validatePolicyRequest(request);

        GuardrailPolicyEntity policy = guardrailPolicyRepository
                .findTopByScopeAndScopeIdOrderByCreatedAtDesc(GuardrailPolicyScope.LOCATION, request.getScopeId())
                .orElseGet(GuardrailPolicyEntity::new);

        policy.setScope(GuardrailPolicyScope.LOCATION);
        policy.setScopeId(request.getScopeId());
        policy.setMinMarginPercent(request.getMinMarginPercent());
        policy.setMaxDiscountPercent(request.getMaxDiscountPercent());
        policy.setAutoApprovalThresholdPercent(request.getAutoApprovalThresholdPercent());

        guardrailPolicyRepository.save(policy);

        LocationPriceOverrideResponseDto dto = new LocationPriceOverrideResponseDto();
        dto.setLocationId(policy.getScopeId());
        return dto;
    }

    @Transactional
    public LocationPriceOverrideResponseDto createOverride(@NonNull LocationPriceOverrideCreateRequestDto request) {
        validateCreateRequest(request);

        GuardrailPolicyEntity policy = guardrailPolicyRepository
                .findTopByScopeAndScopeIdOrderByCreatedAtDesc(GuardrailPolicyScope.LOCATION, request.getLocationId())
                .orElseThrow(() -> new CatalogValidationException(
                        "GUARDRAIL_POLICY_NOT_FOUND: No active LOCATION policy for locationId="
                                + request.getLocationId()));

        BigDecimal discountPercent = calculateDiscountPercent(request.getBasePrice(), request.getOverridePrice());
        if (discountPercent.compareTo(policy.getMaxDiscountPercent()) > 0) {
            throw new CatalogValidationException("MAX_DISCOUNT_EXCEEDED: Discount exceeds "
                    + policy.getMaxDiscountPercent().stripTrailingZeros().toPlainString() + "% maximum.");
        }

        BigDecimal marginPercent = calculateMarginPercent(request.getOverridePrice(), request.getCost());
        if (marginPercent != null && marginPercent.compareTo(policy.getMinMarginPercent()) < 0) {
            throw new CatalogValidationException("MIN_MARGIN_VIOLATION: Margin below "
                    + policy.getMinMarginPercent().stripTrailingZeros().toPlainString() + "% minimum.");
        }

        deactivateExistingActiveOverride(request.getLocationId(), request.getProductId());

        LocationPriceOverrideEntity override = new LocationPriceOverrideEntity();
        override.setLocationId(request.getLocationId());
        override.setProductId(request.getProductId());
        override.setBasePrice(request.getBasePrice());
        override.setCost(request.getCost());
        override.setOverridePrice(request.getOverridePrice());
        override.setDiscountPercent(discountPercent);
        override.setMarginPercent(marginPercent);
        override.setCreatedByUserId(request.getCreatedByUserId());

        if (discountPercent.compareTo(policy.getAutoApprovalThresholdPercent()) <= 0) {
            override.setStatus(PriceOverrideStatus.ACTIVE);
            override.setResolvedAt(Instant.now());
        } else {
            override.setStatus(PriceOverrideStatus.PENDING_APPROVAL);
        }

        LocationPriceOverrideEntity savedOverride = locationPriceOverrideRepository.save(override);

        ApprovalRequestEntity approvalRequest = null;
        if (savedOverride.getStatus() == PriceOverrideStatus.PENDING_APPROVAL) {
            approvalRequest = createApprovalRequest(savedOverride);
        }

        return toResponse(savedOverride, approvalRequest);
    }

    @Transactional(readOnly = true)
    public EffectiveLocationPriceResponseDto getEffectivePrice(@NonNull UUID locationId, @NonNull UUID productId) {
        var activeOverride = locationPriceOverrideRepository
                .findTopByLocationIdAndProductIdAndStatusOrderByCreatedAtDesc(locationId, productId,
                        PriceOverrideStatus.ACTIVE);

        EffectiveLocationPriceResponseDto dto = new EffectiveLocationPriceResponseDto();
        dto.setLocationId(locationId);
        dto.setProductId(productId);

        if (activeOverride.isPresent()) {
            dto.setBasePrice(activeOverride.get().getBasePrice());
            dto.setEffectivePrice(activeOverride.get().getOverridePrice());
            dto.setOverrideStatus(PriceOverrideStatus.ACTIVE);
            return dto;
        }

        var pendingOverride = locationPriceOverrideRepository
                .findTopByLocationIdAndProductIdAndStatusOrderByCreatedAtDesc(locationId, productId,
                        PriceOverrideStatus.PENDING_APPROVAL);

        if (pendingOverride.isPresent()) {
            dto.setBasePrice(pendingOverride.get().getBasePrice());
            dto.setEffectivePrice(pendingOverride.get().getBasePrice());
            dto.setOverrideStatus(PriceOverrideStatus.PENDING_APPROVAL);
            return dto;
        }

        throw new CatalogNotFoundException(
                "EFFECTIVE_PRICE_NOT_FOUND: No active or pending override exists for locationId=" + locationId
                        + " and productId=" + productId);
    }

    @Transactional
    public LocationPriceOverrideResponseDto approveOverride(@NonNull UUID overrideId,
            @NonNull LocationPriceOverrideDecisionRequestDto request) {
        validateDecisionRequest(request, false);

        LocationPriceOverrideEntity override = locationPriceOverrideRepository.findById(overrideId)
                .orElseThrow(() -> new CatalogNotFoundException("OVERRIDE_NOT_FOUND: overrideId=" + overrideId));

        validateVersion(request.getVersion(), override.getVersion());

        if (override.getStatus() != PriceOverrideStatus.PENDING_APPROVAL) {
            throw new CatalogValidationException("INVALID_STATUS: Override is not pending approval.");
        }

        override.setStatus(PriceOverrideStatus.ACTIVE);
        override.setApprovedByUserId(request.getActorUserId());
        override.setApprovedAt(Instant.now());
        override.setResolvedAt(Instant.now());

        LocationPriceOverrideEntity saved = locationPriceOverrideRepository.save(override);

        ApprovalRequestEntity approvalRequest = approvalRequestRepository.findByOverrideId(overrideId)
                .orElseThrow(() -> new CatalogNotFoundException(
                        "APPROVAL_REQUEST_NOT_FOUND: overrideId=" + overrideId));
        approvalRequest.setStatus(ApprovalRequestStatus.APPROVED);
        approvalRequest.setResolvedAt(Instant.now());
        ApprovalRequestEntity savedApproval = approvalRequestRepository.save(approvalRequest);

        return toResponse(saved, savedApproval);
    }

    @Transactional
    public LocationPriceOverrideResponseDto rejectOverride(@NonNull UUID overrideId,
            @NonNull LocationPriceOverrideDecisionRequestDto request) {
        validateDecisionRequest(request, true);

        LocationPriceOverrideEntity override = locationPriceOverrideRepository.findById(overrideId)
                .orElseThrow(() -> new CatalogNotFoundException("OVERRIDE_NOT_FOUND: overrideId=" + overrideId));

        validateVersion(request.getVersion(), override.getVersion());

        if (override.getStatus() != PriceOverrideStatus.PENDING_APPROVAL) {
            throw new CatalogValidationException("INVALID_STATUS: Override is not pending approval.");
        }

        override.setStatus(PriceOverrideStatus.REJECTED);
        override.setRejectedBy(request.getActorUserId());
        override.setRejectedAt(Instant.now());
        override.setRejectionReasonCode(request.getRejectionReasonCode());
        override.setRejectionNotes(request.getRejectionNotes());
        override.setResolvedAt(Instant.now());

        LocationPriceOverrideEntity saved = locationPriceOverrideRepository.save(override);

        ApprovalRequestEntity approvalRequest = approvalRequestRepository.findByOverrideId(overrideId)
                .orElseThrow(() -> new CatalogNotFoundException(
                        "APPROVAL_REQUEST_NOT_FOUND: overrideId=" + overrideId));
        approvalRequest.setStatus(ApprovalRequestStatus.REJECTED);
        approvalRequest.setResolvedAt(Instant.now());
        ApprovalRequestEntity savedApproval = approvalRequestRepository.save(approvalRequest);

        return toResponse(saved, savedApproval);
    }

    private void deactivateExistingActiveOverride(UUID locationId, UUID productId) {
        List<LocationPriceOverrideEntity> existingActive = locationPriceOverrideRepository
                .findByLocationIdAndProductIdAndStatus(locationId, productId, PriceOverrideStatus.ACTIVE);

        for (LocationPriceOverrideEntity active : existingActive) {
            active.setStatus(PriceOverrideStatus.INACTIVE);
            active.setResolvedAt(Instant.now());
            locationPriceOverrideRepository.save(active);
        }
    }

    private ApprovalRequestEntity createApprovalRequest(LocationPriceOverrideEntity override) {
        ApprovalRequestEntity approvalRequest = new ApprovalRequestEntity();
        approvalRequest.setOverrideId(override.getId());
        approvalRequest.setStatus(ApprovalRequestStatus.PENDING);

        UUID assignedApproverId = deterministicApprover(override.getLocationId(), override.getProductId());
        approvalRequest.setAssignedApproverId(assignedApproverId);
        approvalRequest.setAssignmentStrategy("LOCATION_SCOPE_PRIMARY_THEN_POOL");

        return approvalRequestRepository.save(approvalRequest);
    }

    private UUID deterministicApprover(UUID locationId, UUID productId) {
        String source = locationId + ":" + productId;
        return UUID.nameUUIDFromBytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private BigDecimal calculateDiscountPercent(BigDecimal basePrice, BigDecimal overridePrice) {
        return basePrice.subtract(overridePrice)
                .divide(basePrice, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMarginPercent(BigDecimal overridePrice, BigDecimal cost) {
        if (cost == null) {
            return null;
        }

        return overridePrice.subtract(cost)
                .divide(overridePrice, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private LocationPriceOverrideResponseDto toResponse(LocationPriceOverrideEntity override,
            ApprovalRequestEntity approvalRequest) {
        LocationPriceOverrideResponseDto dto = new LocationPriceOverrideResponseDto();
        dto.setOverrideId(override.getId());
        dto.setVersion(override.getVersion());
        dto.setLocationId(override.getLocationId());
        dto.setProductId(override.getProductId());
        dto.setBasePrice(override.getBasePrice());
        dto.setCost(override.getCost());
        dto.setOverridePrice(override.getOverridePrice());
        dto.setDiscountPercent(override.getDiscountPercent());
        dto.setMarginPercent(override.getMarginPercent());
        dto.setStatus(override.getStatus());
        dto.setCreatedByUserId(override.getCreatedByUserId());
        dto.setCreatedAt(override.getCreatedAt());
        dto.setApprovedByUserId(override.getApprovedByUserId());
        dto.setApprovedAt(override.getApprovedAt());
        dto.setRejectedBy(override.getRejectedBy());
        dto.setRejectedAt(override.getRejectedAt());
        dto.setRejectionReasonCode(override.getRejectionReasonCode());
        dto.setRejectionNotes(override.getRejectionNotes());

        if (approvalRequest != null) {
            dto.setAssignedApproverId(approvalRequest.getAssignedApproverId());
            dto.setAssignmentStrategy(approvalRequest.getAssignmentStrategy());
        }

        return dto;
    }

    private void validatePolicyRequest(GuardrailPolicyUpsertRequestDto request) {
        if (request.getScopeId() == null) {
            throw new CatalogValidationException("INVALID_DATA: scopeId is required.");
        }
        if (request.getMinMarginPercent() == null) {
            throw new CatalogValidationException("INVALID_DATA: minMarginPercent is required.");
        }
        if (request.getMaxDiscountPercent() == null) {
            throw new CatalogValidationException("INVALID_DATA: maxDiscountPercent is required.");
        }
        if (request.getAutoApprovalThresholdPercent() == null) {
            throw new CatalogValidationException("INVALID_DATA: autoApprovalThresholdPercent is required.");
        }
    }

    private void validateCreateRequest(LocationPriceOverrideCreateRequestDto request) {
        if (request.getLocationId() == null) {
            throw new CatalogValidationException("INVALID_DATA: locationId is required.");
        }
        if (request.getProductId() == null) {
            throw new CatalogValidationException("INVALID_DATA: productId is required.");
        }
        if (request.getCreatedByUserId() == null) {
            throw new CatalogValidationException("INVALID_DATA: createdByUserId is required.");
        }
        if (request.getBasePrice() == null || request.getBasePrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CatalogValidationException("INVALID_DATA: basePrice must be positive.");
        }
        if (request.getOverridePrice() == null || request.getOverridePrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CatalogValidationException("INVALID_DATA: overridePrice must be positive.");
        }
        if (request.getCost() != null && request.getCost().compareTo(BigDecimal.ZERO) < 0) {
            throw new CatalogValidationException("INVALID_DATA: cost cannot be negative.");
        }
        if (request.getOverridePrice().compareTo(request.getBasePrice()) > 0) {
            throw new CatalogValidationException("INVALID_DATA: overridePrice cannot exceed basePrice.");
        }
    }

    private void validateDecisionRequest(LocationPriceOverrideDecisionRequestDto request, boolean rejectFlow) {
        if (request.getVersion() == null) {
            throw new CatalogValidationException("INVALID_DATA: version is required.");
        }
        if (request.getActorUserId() == null) {
            throw new CatalogValidationException("INVALID_DATA: actorUserId is required.");
        }
        if (rejectFlow) {
            if (request.getRejectionReasonCode() == null || request.getRejectionReasonCode().isBlank()) {
                throw new CatalogValidationException("INVALID_DATA: rejectionReasonCode is required.");
            }
            if (request.getRejectionNotes() == null || request.getRejectionNotes().isBlank()) {
                throw new CatalogValidationException("INVALID_DATA: rejectionNotes are required.");
            }
        }
    }

    private void validateVersion(Long requestVersion, Long currentVersion) {
        if (!requestVersion.equals(currentVersion)) {
            throw new OptimisticLockException("Version mismatch for location price override update.");
        }
    }
}
