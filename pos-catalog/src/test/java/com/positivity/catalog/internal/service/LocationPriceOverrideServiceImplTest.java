package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.positivity.catalog.internal.repository.ProductRepository;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Location price overrides: guardrail policy decides whether an override is auto-approved or
 * queued for approval, and every resolved decision invalidates the product-detail cache.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LocationPriceOverrideServiceImpl")
class LocationPriceOverrideServiceImplTest {

    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a01");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a02");
    private static final UUID USER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a03");
    private static final UUID OVERRIDE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a04");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private LocationPriceOverrideRepository locationPriceOverrideRepository;

    @Mock
    private GuardrailPolicyRepository guardrailPolicyRepository;

    @Mock
    private ApprovalRequestRepository approvalRequestRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductDetailCacheInvalidationPublisher productDetailCacheInvalidationPublisher;

    private LocationPriceOverrideServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LocationPriceOverrideServiceImpl(
                locationPriceOverrideRepository,
                guardrailPolicyRepository,
                approvalRequestRepository,
                productRepository,
                productDetailCacheInvalidationPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
        when(locationPriceOverrideRepository.save(any())).thenAnswer(invocation -> {
            LocationPriceOverrideEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(OVERRIDE_ID);
            }
            return entity;
        });
        when(approvalRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static GuardrailPolicyEntity policy(String minMargin, String maxDiscount, String autoApproval) {
        GuardrailPolicyEntity policy = new GuardrailPolicyEntity();
        policy.setScope(GuardrailPolicyScope.LOCATION);
        policy.setScopeId(LOCATION_ID);
        policy.setMinMarginPercent(new BigDecimal(minMargin));
        policy.setMaxDiscountPercent(new BigDecimal(maxDiscount));
        policy.setAutoApprovalThresholdPercent(new BigDecimal(autoApproval));
        return policy;
    }

    private void stubPolicy(GuardrailPolicyEntity policy) {
        when(guardrailPolicyRepository.findTopByScopeAndScopeIdOrderByCreatedAtDesc(
                        GuardrailPolicyScope.LOCATION, LOCATION_ID))
                .thenReturn(Optional.ofNullable(policy));
    }

    private static LocationPriceOverrideCreateRequestDto createRequest(String basePrice, String overridePrice) {
        LocationPriceOverrideCreateRequestDto request = new LocationPriceOverrideCreateRequestDto();
        request.setLocationId(LOCATION_ID);
        request.setProductId(PRODUCT_ID);
        request.setCreatedByUserId(USER_ID);
        request.setBasePrice(new BigDecimal(basePrice));
        request.setOverridePrice(new BigDecimal(overridePrice));
        return request;
    }

    private static LocationPriceOverrideDecisionRequestDto decisionRequest(Long version) {
        LocationPriceOverrideDecisionRequestDto request = new LocationPriceOverrideDecisionRequestDto();
        request.setVersion(version);
        request.setActorUserId(USER_ID);
        request.setRejectionReasonCode("PRICE_TOO_LOW");
        request.setRejectionNotes("Below floor for this line.");
        return request;
    }

    private static LocationPriceOverrideEntity override(PriceOverrideStatus status, Long version) {
        LocationPriceOverrideEntity override = new LocationPriceOverrideEntity();
        override.setId(OVERRIDE_ID);
        override.setVersion(version);
        override.setLocationId(LOCATION_ID);
        override.setProductId(PRODUCT_ID);
        override.setBasePrice(new BigDecimal("100.00"));
        override.setOverridePrice(new BigDecimal("90.00"));
        override.setStatus(status);
        return override;
    }

    private static ApprovalRequestEntity approvalRequest() {
        ApprovalRequestEntity approvalRequest = new ApprovalRequestEntity();
        approvalRequest.setOverrideId(OVERRIDE_ID);
        approvalRequest.setStatus(ApprovalRequestStatus.PENDING);
        return approvalRequest;
    }

    @Nested
    @DisplayName("upsertLocationGuardrailPolicy")
    class UpsertLocationGuardrailPolicy {

        private GuardrailPolicyUpsertRequestDto policyRequest() {
            GuardrailPolicyUpsertRequestDto request = new GuardrailPolicyUpsertRequestDto();
            request.setScopeId(LOCATION_ID);
            request.setMinMarginPercent(new BigDecimal("10"));
            request.setMaxDiscountPercent(new BigDecimal("25"));
            request.setAutoApprovalThresholdPercent(new BigDecimal("5"));
            return request;
        }

        @Test
        void createsThePolicyWhenTheLocationHasNoneYet() {
            stubPolicy(null);

            LocationPriceOverrideResponseDto response = service.upsertLocationGuardrailPolicy(policyRequest());

            ArgumentCaptor<GuardrailPolicyEntity> saved = ArgumentCaptor.forClass(GuardrailPolicyEntity.class);
            verify(guardrailPolicyRepository).save(saved.capture());
            assertThat(saved.getValue().getScope()).isEqualTo(GuardrailPolicyScope.LOCATION);
            assertThat(saved.getValue().getScopeId()).isEqualTo(LOCATION_ID);
            assertThat(saved.getValue().getMaxDiscountPercent()).isEqualByComparingTo("25");
            assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        }

        @Test
        void updatesTheLatestExistingPolicyRatherThanAddingAnother() {
            GuardrailPolicyEntity existing = policy("1", "2", "3");
            stubPolicy(existing);

            service.upsertLocationGuardrailPolicy(policyRequest());

            verify(guardrailPolicyRepository).save(existing);
            assertThat(existing.getMinMarginPercent()).isEqualByComparingTo("10");
            assertThat(existing.getAutoApprovalThresholdPercent()).isEqualByComparingTo("5");
        }

        @Test
        void rejectsAPolicyMissingItsScope() {
            GuardrailPolicyUpsertRequestDto request = policyRequest();
            request.setScopeId(null);

            assertThatThrownBy(() -> service.upsertLocationGuardrailPolicy(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("scopeId is required");
        }

        @Test
        void rejectsAPolicyMissingItsMinimumMargin() {
            GuardrailPolicyUpsertRequestDto request = policyRequest();
            request.setMinMarginPercent(null);

            assertThatThrownBy(() -> service.upsertLocationGuardrailPolicy(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("minMarginPercent is required");
        }

        @Test
        void rejectsAPolicyMissingItsMaximumDiscount() {
            GuardrailPolicyUpsertRequestDto request = policyRequest();
            request.setMaxDiscountPercent(null);

            assertThatThrownBy(() -> service.upsertLocationGuardrailPolicy(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("maxDiscountPercent is required");
        }

        @Test
        void rejectsAPolicyMissingItsAutoApprovalThreshold() {
            GuardrailPolicyUpsertRequestDto request = policyRequest();
            request.setAutoApprovalThresholdPercent(null);

            assertThatThrownBy(() -> service.upsertLocationGuardrailPolicy(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("autoApprovalThresholdPercent is required");
        }
    }

    @Nested
    @DisplayName("createOverride")
    class CreateOverride {

        @Test
        void activatesAnOverrideInsideTheAutoApprovalThreshold() {
            stubPolicy(policy("10", "25", "15"));

            LocationPriceOverrideResponseDto response = service.createOverride(createRequest("100.00", "90.00"));

            ArgumentCaptor<LocationPriceOverrideEntity> saved =
                    ArgumentCaptor.forClass(LocationPriceOverrideEntity.class);
            verify(locationPriceOverrideRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(PriceOverrideStatus.ACTIVE);
            assertThat(saved.getValue().getResolvedAt()).isEqualTo(NOW);
            assertThat(saved.getValue().getDiscountPercent()).isEqualByComparingTo("10.0000");
            assertThat(response.getStatus()).isEqualTo(PriceOverrideStatus.ACTIVE);
            // Auto-approved overrides never raise an approval request.
            verifyNoInteractions(approvalRequestRepository);
            verify(productDetailCacheInvalidationPublisher).invalidateProductAtLocation(PRODUCT_ID, LOCATION_ID);
        }

        @Test
        void queuesAnApprovalRequestWhenTheDiscountExceedsTheAutoApprovalThreshold() {
            stubPolicy(policy("10", "25", "5"));

            LocationPriceOverrideResponseDto response = service.createOverride(createRequest("100.00", "90.00"));

            assertThat(response.getStatus()).isEqualTo(PriceOverrideStatus.PENDING_APPROVAL);
            ArgumentCaptor<ApprovalRequestEntity> approval = ArgumentCaptor.forClass(ApprovalRequestEntity.class);
            verify(approvalRequestRepository).save(approval.capture());
            assertThat(approval.getValue().getOverrideId()).isEqualTo(OVERRIDE_ID);
            assertThat(approval.getValue().getStatus()).isEqualTo(ApprovalRequestStatus.PENDING);
            assertThat(approval.getValue().getAssignmentStrategy()).isEqualTo("LOCATION_SCOPE_PRIMARY_THEN_POOL");
            // The approver assignment is derived from location+product, so it is stable per pair.
            assertThat(response.getAssignedApproverId())
                    .isEqualTo(UUID.nameUUIDFromBytes(
                            (LOCATION_ID + ":" + PRODUCT_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Test
        void recordsTheMarginWhenACostIsSupplied() {
            stubPolicy(policy("10", "25", "15"));
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");
            request.setCost(new BigDecimal("45.00"));

            LocationPriceOverrideResponseDto response = service.createOverride(request);

            assertThat(response.getMarginPercent()).isEqualByComparingTo("50.0000");
        }

        @Test
        void leavesTheMarginUnsetWhenNoCostIsKnown() {
            stubPolicy(policy("10", "25", "15"));

            assertThat(service.createOverride(createRequest("100.00", "90.00")).getMarginPercent())
                    .isNull();
        }

        @Test
        void deactivatesAnExistingActiveOverrideForTheSamePair() {
            stubPolicy(policy("10", "25", "15"));
            LocationPriceOverrideEntity existing = override(PriceOverrideStatus.ACTIVE, 0L);
            when(locationPriceOverrideRepository.findByLocationIdAndProductIdAndStatus(
                            LOCATION_ID, PRODUCT_ID, PriceOverrideStatus.ACTIVE))
                    .thenReturn(List.of(existing));

            service.createOverride(createRequest("100.00", "90.00"));

            assertThat(existing.getStatus()).isEqualTo(PriceOverrideStatus.INACTIVE);
            assertThat(existing.getResolvedAt()).isEqualTo(NOW);
            verify(locationPriceOverrideRepository).save(existing);
        }

        @Test
        void rejectsADiscountBeyondThePolicyMaximum() {
            stubPolicy(policy("10", "5", "5"));

            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("MAX_DISCOUNT_EXCEEDED");
            verify(locationPriceOverrideRepository, never()).save(any());
        }

        @Test
        void rejectsAMarginBelowThePolicyMinimum() {
            stubPolicy(policy("60", "25", "15"));
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");
            request.setCost(new BigDecimal("45.00"));

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("MIN_MARGIN_VIOLATION");
        }

        @Test
        void rejectsAnOverrideForALocationWithNoPolicy() {
            stubPolicy(null);

            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("GUARDRAIL_POLICY_NOT_FOUND");
        }

        @Test
        void rejectsAnOverrideForAnUnknownProduct() {
            when(productRepository.existsById(PRODUCT_ID)).thenReturn(false);

            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("PRODUCT_NOT_FOUND");
        }

        @Test
        void rejectsARequestMissingItsLocation() {
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");
            request.setLocationId(null);

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("locationId is required");
        }

        @Test
        void rejectsARequestMissingItsProduct() {
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");
            request.setProductId(null);

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("productId is required");
        }

        @Test
        void rejectsARequestMissingItsAuthor() {
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");
            request.setCreatedByUserId(null);

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("createdByUserId is required");
        }

        @Test
        void rejectsANonPositiveBasePrice() {
            LocationPriceOverrideCreateRequestDto request = createRequest("0.00", "90.00");

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("basePrice must be positive");
        }

        @Test
        void rejectsAMissingBasePrice() {
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");
            request.setBasePrice(null);

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("basePrice must be positive");
        }

        @Test
        void rejectsANonPositiveOverridePrice() {
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "0.00");

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("overridePrice must be positive");
        }

        @Test
        void rejectsAMissingOverridePrice() {
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");
            request.setOverridePrice(null);

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("overridePrice must be positive");
        }

        @Test
        void rejectsANegativeCost() {
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "90.00");
            request.setCost(new BigDecimal("-1"));

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("cost cannot be negative");
        }

        @Test
        void rejectsAnOverridePriceAboveTheBasePrice() {
            LocationPriceOverrideCreateRequestDto request = createRequest("100.00", "110.00");

            assertThatThrownBy(() -> service.createOverride(request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("overridePrice cannot exceed basePrice");
        }
    }

    @Nested
    @DisplayName("getEffectivePrice")
    class GetEffectivePrice {

        @Test
        void servesTheOverridePriceWhileAnActiveOverrideExists() {
            when(locationPriceOverrideRepository.findTopByLocationIdAndProductIdAndStatusOrderByCreatedAtDesc(
                            LOCATION_ID, PRODUCT_ID, PriceOverrideStatus.ACTIVE))
                    .thenReturn(Optional.of(override(PriceOverrideStatus.ACTIVE, 0L)));

            EffectiveLocationPriceResponseDto response = service.getEffectivePrice(LOCATION_ID, PRODUCT_ID);

            assertThat(response.getEffectivePrice()).isEqualByComparingTo("90.00");
            assertThat(response.getOverrideStatus()).isEqualTo(PriceOverrideStatus.ACTIVE);
        }

        @Test
        void keepsChargingTheBasePriceWhileAnOverrideIsStillPending() {
            when(locationPriceOverrideRepository.findTopByLocationIdAndProductIdAndStatusOrderByCreatedAtDesc(
                            LOCATION_ID, PRODUCT_ID, PriceOverrideStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(locationPriceOverrideRepository.findTopByLocationIdAndProductIdAndStatusOrderByCreatedAtDesc(
                            LOCATION_ID, PRODUCT_ID, PriceOverrideStatus.PENDING_APPROVAL))
                    .thenReturn(Optional.of(override(PriceOverrideStatus.PENDING_APPROVAL, 0L)));

            EffectiveLocationPriceResponseDto response = service.getEffectivePrice(LOCATION_ID, PRODUCT_ID);

            assertThat(response.getEffectivePrice()).isEqualByComparingTo("100.00");
            assertThat(response.getOverrideStatus()).isEqualTo(PriceOverrideStatus.PENDING_APPROVAL);
        }

        @Test
        void failsWhenNeitherAnActiveNorAPendingOverrideExists() {
            when(locationPriceOverrideRepository.findTopByLocationIdAndProductIdAndStatusOrderByCreatedAtDesc(
                            any(), any(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getEffectivePrice(LOCATION_ID, PRODUCT_ID))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("EFFECTIVE_PRICE_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("approveOverride")
    class ApproveOverride {

        @Test
        void activatesThePendingOverrideAndClosesItsApprovalRequest() {
            LocationPriceOverrideEntity pending = override(PriceOverrideStatus.PENDING_APPROVAL, 3L);
            when(locationPriceOverrideRepository.findById(OVERRIDE_ID)).thenReturn(Optional.of(pending));
            ApprovalRequestEntity approval = approvalRequest();
            when(approvalRequestRepository.findByOverrideId(OVERRIDE_ID)).thenReturn(Optional.of(approval));

            LocationPriceOverrideResponseDto response = service.approveOverride(OVERRIDE_ID, decisionRequest(3L));

            assertThat(pending.getStatus()).isEqualTo(PriceOverrideStatus.ACTIVE);
            assertThat(pending.getApprovedByUserId()).isEqualTo(USER_ID);
            assertThat(pending.getApprovedAt()).isEqualTo(NOW);
            assertThat(pending.getResolvedAt()).isEqualTo(NOW);
            assertThat(approval.getStatus()).isEqualTo(ApprovalRequestStatus.APPROVED);
            assertThat(approval.getResolvedAt()).isEqualTo(NOW);
            assertThat(response.getStatus()).isEqualTo(PriceOverrideStatus.ACTIVE);
            verify(productDetailCacheInvalidationPublisher).invalidateProductAtLocation(PRODUCT_ID, LOCATION_ID);
        }

        @Test
        void failsWhenTheOverrideDoesNotExist() {
            when(locationPriceOverrideRepository.findById(OVERRIDE_ID)).thenReturn(Optional.empty());
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);

            assertThatThrownBy(() -> service.approveOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("OVERRIDE_NOT_FOUND");
        }

        @Test
        void failsWhenTheCallersVersionIsStale() {
            when(locationPriceOverrideRepository.findById(OVERRIDE_ID))
                    .thenReturn(Optional.of(override(PriceOverrideStatus.PENDING_APPROVAL, 4L)));
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);

            assertThatThrownBy(() -> service.approveOverride(OVERRIDE_ID, request))
                    .isInstanceOf(OptimisticLockException.class);
        }

        @Test
        void failsWhenTheOverrideIsNoLongerPending() {
            when(locationPriceOverrideRepository.findById(OVERRIDE_ID))
                    .thenReturn(Optional.of(override(PriceOverrideStatus.ACTIVE, 3L)));
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);

            assertThatThrownBy(() -> service.approveOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("INVALID_STATUS");
        }

        @Test
        void failsWhenTheApprovalRequestIsMissing() {
            when(locationPriceOverrideRepository.findById(OVERRIDE_ID))
                    .thenReturn(Optional.of(override(PriceOverrideStatus.PENDING_APPROVAL, 3L)));
            when(approvalRequestRepository.findByOverrideId(OVERRIDE_ID)).thenReturn(Optional.empty());
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);

            assertThatThrownBy(() -> service.approveOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("APPROVAL_REQUEST_NOT_FOUND");
        }

        @Test
        void rejectsADecisionCarryingNoVersion() {
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(null);

            assertThatThrownBy(() -> service.approveOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("version is required");
        }

        @Test
        void rejectsADecisionCarryingNoActor() {
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);
            request.setActorUserId(null);

            assertThatThrownBy(() -> service.approveOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("actorUserId is required");
        }
    }

    @Nested
    @DisplayName("rejectOverride")
    class RejectOverride {

        @Test
        void rejectsThePendingOverrideAndClosesItsApprovalRequest() {
            LocationPriceOverrideEntity pending = override(PriceOverrideStatus.PENDING_APPROVAL, 3L);
            when(locationPriceOverrideRepository.findById(OVERRIDE_ID)).thenReturn(Optional.of(pending));
            ApprovalRequestEntity approval = approvalRequest();
            when(approvalRequestRepository.findByOverrideId(OVERRIDE_ID)).thenReturn(Optional.of(approval));

            LocationPriceOverrideResponseDto response = service.rejectOverride(OVERRIDE_ID, decisionRequest(3L));

            assertThat(pending.getStatus()).isEqualTo(PriceOverrideStatus.REJECTED);
            assertThat(pending.getRejectedBy()).isEqualTo(USER_ID);
            assertThat(pending.getRejectionReasonCode()).isEqualTo("PRICE_TOO_LOW");
            assertThat(pending.getRejectionNotes()).isEqualTo("Below floor for this line.");
            assertThat(approval.getStatus()).isEqualTo(ApprovalRequestStatus.REJECTED);
            assertThat(response.getStatus()).isEqualTo(PriceOverrideStatus.REJECTED);
            verify(productDetailCacheInvalidationPublisher).invalidateProductAtLocation(PRODUCT_ID, LOCATION_ID);
        }

        @Test
        void requiresAReasonCode() {
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);
            request.setRejectionReasonCode("  ");

            assertThatThrownBy(() -> service.rejectOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("rejectionReasonCode is required");
        }

        @Test
        void requiresRejectionNotes() {
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);
            request.setRejectionNotes(null);

            assertThatThrownBy(() -> service.rejectOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("rejectionNotes are required");
        }

        @Test
        void failsWhenTheOverrideIsNoLongerPending() {
            when(locationPriceOverrideRepository.findById(OVERRIDE_ID))
                    .thenReturn(Optional.of(override(PriceOverrideStatus.REJECTED, 3L)));
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);

            assertThatThrownBy(() -> service.rejectOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogValidationException.class)
                    .hasMessageContaining("INVALID_STATUS");
        }

        @Test
        void failsWhenTheOverrideDoesNotExist() {
            when(locationPriceOverrideRepository.findById(OVERRIDE_ID)).thenReturn(Optional.empty());
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);

            assertThatThrownBy(() -> service.rejectOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("OVERRIDE_NOT_FOUND");
        }

        @Test
        void failsWhenTheApprovalRequestIsMissing() {
            when(locationPriceOverrideRepository.findById(OVERRIDE_ID))
                    .thenReturn(Optional.of(override(PriceOverrideStatus.PENDING_APPROVAL, 3L)));
            when(approvalRequestRepository.findByOverrideId(OVERRIDE_ID)).thenReturn(Optional.empty());
            LocationPriceOverrideDecisionRequestDto request = decisionRequest(3L);

            assertThatThrownBy(() -> service.rejectOverride(OVERRIDE_ID, request))
                    .isInstanceOf(CatalogNotFoundException.class)
                    .hasMessageContaining("APPROVAL_REQUEST_NOT_FOUND");
        }
    }
}
