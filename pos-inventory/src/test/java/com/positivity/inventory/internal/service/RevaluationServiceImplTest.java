package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.inventory.ProductValueChangedV1;
import com.positivity.inventory.internal.dto.revaluation.CreateRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RejectRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RevaluationResponse;
import com.positivity.inventory.internal.entity.RevaluationRecord;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.RevaluationStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.RevaluationRecordRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.service.ApprovalThresholdEvaluator;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link RevaluationServiceImpl} (odoo-parity J4, issue #1054): value-delta approval
 * gating, STANDARD vs AVERAGE cost restatement, delta = Δcost × on-hand, and fact emission.
 */
@ExtendWith(MockitoExtension.class)
class RevaluationServiceImplTest {

    private static final String ACTOR = "cost-admin";
    private static final String SKU = "OIL-5W30-5QT";

    @Mock
    private RevaluationRecordRepository revaluationRepository;

    @Mock
    private SkuCostStateRepository costStateRepository;

    @Mock
    private SkuCostStateInitializer costStateInitializer;

    @Mock
    private CostingMethodResolver methodResolver;

    @Mock
    private ApprovalThresholdEvaluator thresholdEvaluator;

    @Mock
    private InventoryFactPublisher inventoryFactPublisher;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC);

    private RevaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RevaluationServiceImpl(
                revaluationRepository,
                costStateRepository,
                costStateInitializer,
                methodResolver,
                thresholdEvaluator,
                inventoryFactPublisher,
                fixedClock);
        setUpAuthenticatedActor();
        // Mirror the DB @GeneratedValue: assign a UUIDv7 surrogate id on first save if absent.
        lenient().when(revaluationRepository.save(any(RevaluationRecord.class))).thenAnswer(inv -> {
            RevaluationRecord saved = inv.getArgument(0);
            if (saved.getRevaluationId() == null) {
                saved.setRevaluationId(UUID.randomUUID());
            }
            return saved;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void autoAppliesBelowThresholdRestatesAverageAndEmitsFactWithCorrectDelta() {
        SkuCostState state = state(new BigDecimal("4.000000"), null, 10L);
        when(methodResolver.resolve(SKU)).thenReturn(CostingMethod.AVERAGE);
        when(costStateRepository.findByStockItemId(SKU)).thenReturn(Optional.of(state));
        // delta = (5.00 - 4.00) * 10 = 10.00, below all thresholds -> auto-apply
        when(thresholdEvaluator.evaluateRevaluationApprovalTier(any())).thenReturn(Optional.empty());

        RevaluationResponse response = service.createRevaluation(request(new BigDecimal("5.0000"), null));

        assertThat(response.getStatus()).isEqualTo(RevaluationStatus.AUTO_APPLIED);
        assertThat(response.getValueDelta()).isEqualByComparingTo("10.00");
        assertThat(response.getCostingMethod()).isEqualTo(CostingMethod.AVERAGE);

        // AVERAGE restates avgCost (not standardCost)
        assertThat(state.getAvgCost()).isEqualByComparingTo("5.00");
        assertThat(state.getStandardCost()).isNull();
        verify(costStateRepository).save(state);

        // value delta passed to the evaluator == Δcost × on-hand
        ArgumentCaptor<BigDecimal> deltaCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(thresholdEvaluator).evaluateRevaluationApprovalTier(deltaCaptor.capture());
        assertThat(deltaCaptor.getValue()).isEqualByComparingTo("10.00");

        // fact carries old/new cost + delta
        ArgumentCaptor<ProductValueChangedV1> factCaptor = ArgumentCaptor.forClass(ProductValueChangedV1.class);
        verify(inventoryFactPublisher).recordProductValueChanged(factCaptor.capture());
        ProductValueChangedV1 fact = factCaptor.getValue();
        assertThat(fact.sku()).isEqualTo(SKU);
        assertThat(fact.costingMethod()).isEqualTo("AVERAGE");
        assertThat(fact.previousUnitCost()).isEqualByComparingTo("4.00");
        assertThat(fact.newUnitCost()).isEqualByComparingTo("5.00");
        assertThat(fact.onHandQuantity()).isEqualTo(10L);
        assertThat(fact.totalValueDelta()).isEqualByComparingTo("10.00");
        assertThat(fact.actor()).isEqualTo(ACTOR);
    }

    @Test
    void aboveThresholdStaysPendingWithNoCostChangeOrFact() {
        SkuCostState state = state(new BigDecimal("4.000000"), null, 200L);
        when(methodResolver.resolve(SKU)).thenReturn(CostingMethod.AVERAGE);
        when(costStateRepository.findByStockItemId(SKU)).thenReturn(Optional.of(state));
        // delta = (9.00 - 4.00) * 200 = 1000.00 -> director tier required
        when(thresholdEvaluator.evaluateRevaluationApprovalTier(any()))
                .thenReturn(Optional.of(ApprovalTier.TIER_2_DIRECTOR));

        RevaluationResponse response = service.createRevaluation(request(new BigDecimal("9.0000"), null));

        assertThat(response.getStatus()).isEqualTo(RevaluationStatus.PENDING_APPROVAL);
        assertThat(response.getRequiredApprovalTier()).isEqualTo(ApprovalTier.TIER_2_DIRECTOR);
        assertThat(response.getValueDelta()).isEqualByComparingTo("1000.00");
        // no cost change, no fact until approved
        assertThat(state.getAvgCost()).isEqualByComparingTo("4.00");
        verify(costStateRepository, never()).save(any());
        verifyNoInteractions(inventoryFactPublisher);
    }

    @Test
    void approveAppliesStandardCostAndEmitsFact() {
        RevaluationRecord pending = RevaluationRecord.builder()
                .revaluationId(UUID.randomUUID())
                .stockItemId(SKU)
                .costingMethod(CostingMethod.STANDARD)
                .previousUnitCost(new BigDecimal("6.000000"))
                .newUnitCost(new BigDecimal("8.000000"))
                .onHandQuantity(150L)
                .valueDelta(new BigDecimal("300.0000"))
                .reason("Standard price correction")
                .status(RevaluationStatus.PENDING_APPROVAL)
                .requiredApprovalTier(ApprovalTier.TIER_1_MANAGER)
                .createdBy("submitter")
                .build();
        SkuCostState state = state(new BigDecimal("2.000000"), new BigDecimal("6.000000"), 150L);
        when(revaluationRepository.findById(pending.getRevaluationId())).thenReturn(Optional.of(pending));
        when(costStateRepository.findByStockItemId(SKU)).thenReturn(Optional.of(state));

        RevaluationResponse response = service.approveRevaluation(pending.getRevaluationId());

        assertThat(response.getStatus()).isEqualTo(RevaluationStatus.APPLIED);
        assertThat(response.getApprovedBy()).isEqualTo(ACTOR);
        // STANDARD restates standardCost (not avgCost)
        assertThat(state.getStandardCost()).isEqualByComparingTo("8.00");
        assertThat(state.getAvgCost()).isEqualByComparingTo("2.00");
        verify(costStateRepository).save(state);

        ArgumentCaptor<ProductValueChangedV1> factCaptor = ArgumentCaptor.forClass(ProductValueChangedV1.class);
        verify(inventoryFactPublisher).recordProductValueChanged(factCaptor.capture());
        ProductValueChangedV1 fact = factCaptor.getValue();
        assertThat(fact.costingMethod()).isEqualTo("STANDARD");
        assertThat(fact.newUnitCost()).isEqualByComparingTo("8.00");
        assertThat(fact.totalValueDelta()).isEqualByComparingTo("300.00");
        assertThat(fact.actor()).isEqualTo(ACTOR);
    }

    @Test
    void rejectLeavesCostStateUntouched() {
        RevaluationRecord pending = RevaluationRecord.builder()
                .revaluationId(UUID.randomUUID())
                .stockItemId(SKU)
                .costingMethod(CostingMethod.AVERAGE)
                .newUnitCost(new BigDecimal("9.000000"))
                .onHandQuantity(200L)
                .valueDelta(new BigDecimal("1000.0000"))
                .reason("r")
                .status(RevaluationStatus.PENDING_APPROVAL)
                .createdBy("submitter")
                .build();
        when(revaluationRepository.findById(pending.getRevaluationId())).thenReturn(Optional.of(pending));

        RevaluationResponse response =
                service.rejectRevaluation(pending.getRevaluationId(), reject("Disputed recount"));

        assertThat(response.getStatus()).isEqualTo(RevaluationStatus.REJECTED);
        assertThat(response.getRejectedBy()).isEqualTo(ACTOR);
        assertThat(response.getRejectionReason()).isEqualTo("Disputed recount");
        verify(costStateRepository, never()).save(any());
        verifyNoInteractions(inventoryFactPublisher);
    }

    @Test
    void costDeltaResolvesNewCostFromCurrent() {
        SkuCostState state = state(new BigDecimal("4.000000"), null, 10L);
        when(methodResolver.resolve(SKU)).thenReturn(CostingMethod.AVERAGE);
        when(costStateRepository.findByStockItemId(SKU)).thenReturn(Optional.of(state));
        when(thresholdEvaluator.evaluateRevaluationApprovalTier(any())).thenReturn(Optional.empty());

        // costDelta -0.50 on current 4.00 => new 3.50, delta = -0.50 * 10 = -5.00
        RevaluationResponse response = service.createRevaluation(request(null, new BigDecimal("-0.5000")));

        assertThat(response.getNewUnitCost()).isEqualByComparingTo("3.50");
        assertThat(response.getValueDelta()).isEqualByComparingTo("-5.00");
        assertThat(state.getAvgCost()).isEqualByComparingTo("3.50");
    }

    @Test
    void rejectsWhenNeitherOrBothCostFieldsSupplied() {
        when(methodResolver.resolve(SKU)).thenReturn(CostingMethod.AVERAGE);
        when(costStateRepository.findByStockItemId(SKU))
                .thenReturn(Optional.of(state(new BigDecimal("4.000000"), null, 10L)));

        assertThatThrownBy(() -> service.createRevaluation(request(null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> service.createRevaluation(request(new BigDecimal("5.0000"), new BigDecimal("1.0000"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void missingRevaluationThrowsNotFound() {
        UUID unknown = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        when(revaluationRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveRevaluation(unknown)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.rejectRevaluation(unknown, new RejectRevaluationRequest("no")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getRevaluation(unknown)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void seedsCostStateForUnseenSku() {
        SkuCostState seeded = state(null, null, 0L);
        when(methodResolver.resolve(SKU)).thenReturn(CostingMethod.STANDARD);
        when(costStateRepository.findByStockItemId(SKU))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(seeded));
        when(thresholdEvaluator.evaluateRevaluationApprovalTier(any())).thenReturn(Optional.empty());

        RevaluationResponse response = service.createRevaluation(request(new BigDecimal("6.0000"), null));

        verify(costStateInitializer).createRowIfAbsent(SKU);
        assertThat(response.getStatus()).isEqualTo(RevaluationStatus.AUTO_APPLIED);
        assertThat(response.getPreviousUnitCost()).isNull();
        // onHand 0 => value delta 0
        assertThat(response.getValueDelta()).isEqualByComparingTo("0.00");
        assertThat(seeded.getStandardCost()).isEqualByComparingTo("6.00");
    }

    private SkuCostState state(BigDecimal avgCost, BigDecimal standardCost, long onHand) {
        return SkuCostState.builder()
                .costStateId(UUID.randomUUID())
                .stockItemId(SKU)
                .avgCost(avgCost)
                .standardCost(standardCost)
                .onHandQty(onHand)
                .build();
    }

    private CreateRevaluationRequest request(BigDecimal newUnitCost, BigDecimal costDelta) {
        return CreateRevaluationRequest.builder()
                .stockItemId(SKU)
                .newUnitCost(newUnitCost)
                .costDelta(costDelta)
                .reason("Q3 recount correction")
                .build();
    }

    private RejectRevaluationRequest reject(String reason) {
        return RejectRevaluationRequest.builder().rejectionReason(reason).build();
    }

    private void setUpAuthenticatedActor() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(ACTOR, "password");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID,
                "actor-person-id-001",
                GatewaySecurityConstants.DETAIL_USERNAME,
                ACTOR));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
