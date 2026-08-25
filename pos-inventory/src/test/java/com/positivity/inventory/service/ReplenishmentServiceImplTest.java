package com.positivity.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.ReplenishmentDecisionReason;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.enums.ReplenishmentTriggerType;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ReplenishmentTaskRepository;
import com.positivity.inventory.internal.service.ForecastQuantityService;
import com.positivity.inventory.internal.service.ForecastSiteResolver;
import com.positivity.inventory.internal.service.LeadTimeResolver;
import com.positivity.inventory.internal.service.ReplenishmentServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ReplenishmentServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final UUID SRC_01 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DST_01 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SRC_02 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DST_02 = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID LOC_01 = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID LOC_02 = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID PICKFACE_01 = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Spy
    Clock clock = TEST_CLOCK;

    @InjectMocks
    private ReplenishmentServiceImpl replenishmentService;

    @Mock
    private ReplenishmentTaskRepository replenishmentTaskRepository;

    @Mock
    private ReplenishmentPolicyRepository replenishmentPolicyRepository;

    @Mock
    private InventoryStockSummaryRepository stockSummaryRepository;

    @Mock
    private ForecastQuantityService forecastQuantityService;

    @Mock
    private ForecastSiteResolver forecastSiteResolver;

    @Mock
    private LeadTimeResolver leadTimeResolver;

    @Mock
    private com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository
            normalizedAvailabilityRepository;

    /** F3 (#1041): deadline computation is mocked out — the unit vectors here don't exercise it. */
    @Mock
    private com.positivity.inventory.internal.service.StockoutDeadlineCalculator stockoutDeadlineCalculator;

    /** F4 (#1044): open-suggestion netting — defaulted to 0 so pre-F4 vectors are unaffected. */
    @Mock
    private com.positivity.inventory.internal.service.PurchaseSuggestionCreationService
            purchaseSuggestionCreationService;

    /**
     * F5 (#1045): sourcing resolution. Defaulted to the pre-F5 behaviour — no open source
     * transfer, and every triggered need materializes a same-site BELOW_MIN task sourced from
     * the policy's own location — so the pre-F5 unit vectors (quantity / decision reason /
     * location assertions) are unaffected. The real internal-sourcing branches are covered by
     * {@code ReplenishmentSourcingServiceTest} and the F5 scan integration tests.
     */
    @Mock
    private com.positivity.inventory.internal.service.ReplenishmentSourcingService replenishmentSourcingService;

    /**
     * F2 equivalence defaults: no feed lead time (DEFAULT source, 0 days), the policy
     * location is already a site, and no open supply/demand documents — so
     * {@code projectedAvailable == onHand} at any horizon and the forecast-aware trigger
     * and quantity math degrade exactly to the pre-F2 on-hand behavior. Individual tests
     * override the forecast where the projection matters.
     */
    @BeforeEach
    void defaultForecastEqualsOnHand() {
        Mockito.lenient()
                .when(leadTimeResolver.resolve(any()))
                .thenReturn(new LeadTimeResolver.ResolvedLeadTime(0, LeadTimeResolver.LeadTimeSource.DEFAULT));
        Mockito.lenient()
                .when(forecastSiteResolver.resolveForecastSite(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.lenient()
                .when(forecastQuantityService.forecast(any(), any(), any(), any()))
                .thenAnswer(invocation -> new ForecastQuantityService.ForecastQuantities(
                        BigDecimal.ZERO, BigDecimal.ZERO, invocation.getArgument(3, BigDecimal.class)));
        Mockito.lenient()
                .when(replenishmentTaskRepository.findByItemSKUAndDestinationLocationIdAndStatusIn(any(), any(), any()))
                .thenReturn(Collections.emptyList());
        Mockito.lenient()
                .when(purchaseSuggestionCreationService.openSuggestionQuantity(any(), any(), any()))
                .thenReturn(0L);
        Mockito.lenient()
                .when(replenishmentSourcingService.hasOpenSourceTransfer(any()))
                .thenReturn(false);
        Mockito.lenient()
                .when(replenishmentSourcingService.resolve(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation ->
                        new com.positivity.inventory.internal.service.ReplenishmentSourcingService.SourcingResolution(
                                com.positivity.inventory.internal.service.ReplenishmentSourcingService.Kind
                                        .MATERIALIZE_TASK,
                                invocation
                                        .getArgument(0, ReplenishmentPolicy.class)
                                        .getLocationId(),
                                null,
                                null,
                                ReplenishmentDecisionReason.BELOW_MIN));
    }

    @Test
    void getReplenishmentTasks_shouldReturnMappedTasks() {
        // Given
        UUID taskId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID taskId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant now = Instant.now(TEST_CLOCK);

        ReplenishmentTask pendingTask = ReplenishmentTask.builder()
                .taskId(taskId1)
                .itemSKU("SKU123")
                .quantity(10)
                .sourceLocationId(SRC_01)
                .destinationLocationId(DST_01)
                .status(ReplenishmentStatus.PENDING)
                .createdAt(now)
                .build();

        ReplenishmentTask inProgressTask = ReplenishmentTask.builder()
                .taskId(taskId2)
                .itemSKU("SKU456")
                .quantity(5)
                .sourceLocationId(SRC_02)
                .destinationLocationId(DST_02)
                .status(ReplenishmentStatus.IN_PROGRESS)
                .createdAt(now)
                .build();

        when(replenishmentTaskRepository.findByStatusIn(
                        List.of(ReplenishmentStatus.PENDING, ReplenishmentStatus.IN_PROGRESS)))
                .thenReturn(List.of(pendingTask, inProgressTask));

        // When
        List<ReplenishmentTaskResponse> responses = replenishmentService.getReplenishmentTasks();

        // Then
        assertEquals(2, responses.size());

        ReplenishmentTaskResponse response1 = responses.stream()
                .filter(r -> r.getTaskId().equals(taskId1.toString()))
                .findFirst()
                .get();
        assertEquals("SKU123", response1.getItemSKU());
        assertEquals(10, response1.getQuantity());
        assertEquals("PENDING", response1.getStatus());

        ReplenishmentTaskResponse response2 = responses.stream()
                .filter(r -> r.getTaskId().equals(taskId2.toString()))
                .findFirst()
                .get();
        assertEquals("SKU456", response2.getItemSKU());
        assertEquals(5, response2.getQuantity());
        assertEquals("IN_PROGRESS", response2.getStatus());
    }

    @Test
    void getReplenishmentTasks_shouldReturnEmptyListWhenNoTasks() {
        // Given
        when(replenishmentTaskRepository.findByStatusIn(
                        List.of(ReplenishmentStatus.PENDING, ReplenishmentStatus.IN_PROGRESS)))
                .thenReturn(Collections.emptyList());

        // When
        List<ReplenishmentTaskResponse> responses = replenishmentService.getReplenishmentTasks();

        // Then
        assertTrue(responses.isEmpty());
    }

    @Test
    void getReplenishmentPolicies_shouldReturnMappedPolicies() {
        // Given
        UUID policyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant now = Instant.now(TEST_CLOCK);
        ReplenishmentPolicy policy = ReplenishmentPolicy.builder()
                .policyId(policyId)
                .locationId(LOC_01)
                .itemSKU("SKU789")
                .minimumQuantity(5)
                .maximumQuantity(20)
                .createdAt(now)
                .build();

        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));

        // When
        Page<ReplenishmentPolicyResponse> responses =
                replenishmentService.getReplenishmentPolicies(null, Pageable.unpaged());

        // Then
        assertEquals(1, responses.getTotalElements());
        ReplenishmentPolicyResponse response = responses.getContent().get(0);
        assertEquals(policyId.toString(), response.getPolicyId());
        assertEquals(LOC_01, response.getLocationId());
        assertEquals("SKU789", response.getItemSKU());
        assertEquals(5, response.getMinimumQuantity());
        assertEquals(20, response.getMaximumQuantity());
    }

    @Test
    void createReplenishmentPolicy_shouldSaveAndReturnMappedPolicy() {
        // Given
        CreateReplenishmentPolicyRequest request = new CreateReplenishmentPolicyRequest();
        request.setLocationId(LOC_02);
        request.setItemSKU("SKUABC");
        request.setMinimumQuantity(10);
        request.setMaximumQuantity(50);
        UUID policyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant now = Instant.now(TEST_CLOCK);

        ReplenishmentPolicy savedPolicy = ReplenishmentPolicy.builder()
                .policyId(policyId)
                .locationId(request.getLocationId())
                .itemSKU(request.getItemSKU())
                .minimumQuantity(request.getMinimumQuantity())
                .maximumQuantity(request.getMaximumQuantity())
                .createdAt(now)
                .build();

        when(replenishmentPolicyRepository.save(any(ReplenishmentPolicy.class))).thenReturn(savedPolicy);

        // When
        ReplenishmentPolicyResponse response = replenishmentService.createReplenishmentPolicy(request);

        // Then
        assertNotNull(response);
        assertEquals(policyId.toString(), response.getPolicyId());
        assertEquals(LOC_02, response.getLocationId());
        assertEquals("SKUABC", response.getItemSKU());
        assertEquals(10, response.getMinimumQuantity());
        assertEquals(50, response.getMaximumQuantity());
    }

    @Test
    void evaluatePickFaceForReplenishment_shouldReturnNoAction() {
        // Given
        when(replenishmentPolicyRepository.findByLocationId(any())).thenReturn(Collections.emptyList());

        // When
        ReplenishmentTaskResponse response =
                replenishmentService.evaluatePickFaceForReplenishment("PROD123", PICKFACE_01);

        // Then
        assertNotNull(response);
        assertEquals("NO_ACTION", response.getStatus());
    }

    @Test
    void evaluatePickFaceForReplenishment_shouldReturnTaskAlreadyQueued_whenTaskAlreadyExists() {
        // Given - a policy exists for the SKU at the location
        when(replenishmentPolicyRepository.findByLocationId(any()))
                .thenReturn(List.of(ReplenishmentPolicy.builder()
                        .locationId(PICKFACE_01)
                        .itemSKU("PROD123")
                        .minimumQuantity(5)
                        .maximumQuantity(20)
                        .build()));
        // AND - a pending/in-progress replenishment task already exists for that
        // sku+location
        when(replenishmentTaskRepository.existsByItemSKUAndDestinationLocationIdAndStatusIn(any(), any(), any()))
                .thenReturn(true);

        // When
        ReplenishmentTaskResponse response =
                replenishmentService.evaluatePickFaceForReplenishment("PROD123", PICKFACE_01);

        // Then — the open task IS the in-progress supply for this breach: no new task, no
        // added quantity (F2 no-double-ordering across scan and event paths).
        assertNotNull(response);
        assertEquals("TASK_ALREADY_QUEUED", response.getStatus());
        org.mockito.Mockito.verify(replenishmentTaskRepository, org.mockito.Mockito.never())
                .save(any());
    }

    @Test
    void evaluatePickFaceForReplenishment_projectedAtOrAboveMinimum_returnsNoAction() {
        when(replenishmentPolicyRepository.findByLocationId(PICKFACE_01))
                .thenReturn(List.of(ReplenishmentPolicy.builder()
                        .locationId(PICKFACE_01)
                        .itemSKU("PROD123")
                        .minimumQuantity(5)
                        .maximumQuantity(20)
                        .build()));
        when(replenishmentTaskRepository.existsByItemSKUAndDestinationLocationIdAndStatusIn(any(), any(), any()))
                .thenReturn(false);
        givenOnHand("PROD123", PICKFACE_01, 3);
        // Inbound supply within the horizon lifts the projection to the minimum.
        when(forecastQuantityService.forecast(any(), any(), any(), any()))
                .thenReturn(new ForecastQuantityService.ForecastQuantities(
                        new BigDecimal("2"), new BigDecimal("0"), new BigDecimal("5")));

        ReplenishmentTaskResponse response =
                replenishmentService.evaluatePickFaceForReplenishment("PROD123", PICKFACE_01);

        assertEquals("NO_ACTION", response.getStatus());
        org.mockito.Mockito.verify(replenishmentTaskRepository, org.mockito.Mockito.never())
                .save(any());
    }

    @Test
    void evaluatePickFaceForReplenishment_projectedBelowMinimum_createsEventTaskToMax() {
        when(replenishmentPolicyRepository.findByLocationId(PICKFACE_01))
                .thenReturn(List.of(ReplenishmentPolicy.builder()
                        .locationId(PICKFACE_01)
                        .itemSKU("PROD123")
                        .minimumQuantity(5)
                        .maximumQuantity(20)
                        .build()));
        when(replenishmentTaskRepository.existsByItemSKUAndDestinationLocationIdAndStatusIn(any(), any(), any()))
                .thenReturn(false);
        givenOnHand("PROD123", PICKFACE_01, 3);
        when(replenishmentTaskRepository.save(any(ReplenishmentTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReplenishmentTaskResponse response =
                replenishmentService.evaluatePickFaceForReplenishment("PROD123", PICKFACE_01);

        assertEquals("PENDING", response.getStatus());
        org.mockito.ArgumentCaptor<ReplenishmentTask> captor =
                org.mockito.ArgumentCaptor.forClass(ReplenishmentTask.class);
        org.mockito.Mockito.verify(replenishmentTaskRepository).save(captor.capture());
        ReplenishmentTask saved = captor.getValue();
        assertEquals("PROD123", saved.getItemSKU());
        assertEquals(17, saved.getQuantity()); // max 20 - projected 3
        assertEquals(ReplenishmentTriggerType.EVENT, saved.getTriggerType());
        assertEquals(ReplenishmentDecisionReason.BELOW_MIN, saved.getDecisionReason());
    }

    // ─── runBatchReplenishmentScan (CAP-217 / odoo-parity F1, issue #1025) ────

    private ReplenishmentPolicy policy(UUID locationId, String sku, int min, int max) {
        return ReplenishmentPolicy.builder()
                .policyId(UUID.fromString("00000000-0000-0000-0000-00000000000f"))
                .locationId(locationId)
                .itemSKU(sku)
                .minimumQuantity(min)
                .maximumQuantity(max)
                .build();
    }

    private void givenOnHand(String sku, UUID locationId, long onHand) {
        when(stockSummaryRepository.findByStockItemIdAndLocationId(sku, locationId))
                .thenReturn(java.util.Optional.of(InventoryStockSummary.builder()
                        .stockItemId(sku)
                        .locationId(locationId)
                        .onHand(BigDecimal.valueOf(onHand))
                        .build()));
    }

    @Test
    void runBatchReplenishmentScan_withNoPolicies_reportsZeroActivity() {
        when(replenishmentPolicyRepository.findAll()).thenReturn(Collections.emptyList());

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(0, result.getPoliciesEvaluated());
        assertEquals(0, result.getTasksCreated());
        assertEquals(0, result.getTasksRefreshed());
    }

    @Test
    void runBatchReplenishmentScan_belowMinimum_createsBatchTaskToMaximum() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-LOW", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-LOW", LOC_01, 3);
        when(replenishmentTaskRepository.findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                        any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(replenishmentTaskRepository
                        .existsByItemSKUAndDestinationLocationIdAndTriggerTypeAndCreatedAtGreaterThanEqual(
                                any(), any(), any(), any()))
                .thenReturn(false);

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(1, result.getPoliciesEvaluated());
        assertEquals(1, result.getPoliciesBelowMinimum());
        assertEquals(1, result.getTasksCreated());

        org.mockito.ArgumentCaptor<ReplenishmentTask> captor =
                org.mockito.ArgumentCaptor.forClass(ReplenishmentTask.class);
        org.mockito.Mockito.verify(replenishmentTaskRepository).save(captor.capture());
        ReplenishmentTask saved = captor.getValue();
        assertEquals("SKU-LOW", saved.getItemSKU());
        assertEquals(17, saved.getQuantity()); // max 20 - onHand 3
        assertEquals(LOC_01, saved.getDestinationLocationId());
        assertEquals(ReplenishmentStatus.PENDING, saved.getStatus());
        assertEquals(ReplenishmentTriggerType.BATCH, saved.getTriggerType());
        assertEquals(ReplenishmentDecisionReason.BELOW_MIN, saved.getDecisionReason());
    }

    @Test
    void runBatchReplenishmentScan_atOrAboveMinimum_createsNothing() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-OK", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-OK", LOC_01, 5); // exactly at minimum → not below

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(1, result.getPoliciesEvaluated());
        assertEquals(0, result.getPoliciesBelowMinimum());
        assertEquals(0, result.getTasksCreated());
        org.mockito.Mockito.verify(replenishmentTaskRepository, org.mockito.Mockito.never())
                .save(any());
    }

    @Test
    void runBatchReplenishmentScan_missingSummaryRow_treatedAsZeroOnHand() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-NEW", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        when(stockSummaryRepository.findByStockItemIdAndLocationId("SKU-NEW", LOC_01))
                .thenReturn(java.util.Optional.empty());
        when(replenishmentTaskRepository.findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                        any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(replenishmentTaskRepository
                        .existsByItemSKUAndDestinationLocationIdAndTriggerTypeAndCreatedAtGreaterThanEqual(
                                any(), any(), any(), any()))
                .thenReturn(false);

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(1, result.getTasksCreated());
        org.mockito.ArgumentCaptor<ReplenishmentTask> captor =
                org.mockito.ArgumentCaptor.forClass(ReplenishmentTask.class);
        org.mockito.Mockito.verify(replenishmentTaskRepository).save(captor.capture());
        assertEquals(20, captor.getValue().getQuantity()); // max 20 - onHand 0
    }

    @Test
    void runBatchReplenishmentScan_openTaskExists_refreshesQuantityInsteadOfDuplicating() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-LOW", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-LOW", LOC_01, 2);
        ReplenishmentTask openTask = ReplenishmentTask.builder()
                .taskId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
                .itemSKU("SKU-LOW")
                .quantity(15)
                .sourceLocationId(LOC_01)
                .destinationLocationId(LOC_01)
                .status(ReplenishmentStatus.PENDING)
                .triggerType(ReplenishmentTriggerType.BATCH)
                .build();
        when(replenishmentTaskRepository.findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                        any(), any(), any()))
                .thenReturn(java.util.Optional.of(openTask));

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(0, result.getTasksCreated());
        assertEquals(1, result.getTasksRefreshed());
        assertEquals(18, openTask.getQuantity()); // refreshed to max 20 - onHand 2
        org.mockito.Mockito.verify(replenishmentTaskRepository).save(openTask);
    }

    @Test
    void runBatchReplenishmentScan_taskAlreadyCreatedToday_skipsCreation() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-LOW", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-LOW", LOC_01, 2);
        when(replenishmentTaskRepository.findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                        any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(replenishmentTaskRepository
                        .existsByItemSKUAndDestinationLocationIdAndTriggerTypeAndCreatedAtGreaterThanEqual(
                                any(), any(), any(), any()))
                .thenReturn(true);

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(1, result.getPoliciesBelowMinimum());
        assertEquals(0, result.getTasksCreated());
        org.mockito.Mockito.verify(replenishmentTaskRepository, org.mockito.Mockito.never())
                .save(any());
    }

    // ─── F2 (#1040): Odoo-derived orderpoint vectors — forecast-aware trigger math ────

    /**
     * Odoo vector: on-hand below minimum but an inbound PO inside the lead horizon lifts
     * {@code projectedAvailable} to the minimum — NO trigger (the pre-F2 on-hand math
     * would have fired).
     */
    @Test
    void runBatchReplenishmentScan_inboundSupplyWithinHorizonCoversGap_noTrigger() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-INBOUND", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-INBOUND", LOC_01, 3);
        // Inbound 7 due within the horizon: projected 10 >= min 5.
        when(forecastQuantityService.forecast(any(), any(), any(), any()))
                .thenReturn(new ForecastQuantityService.ForecastQuantities(
                        new BigDecimal("7"), new BigDecimal("0"), new BigDecimal("10")));

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(1, result.getPoliciesEvaluated());
        assertEquals(0, result.getPoliciesBelowMinimum());
        assertEquals(0, result.getTasksCreated());
        org.mockito.Mockito.verify(replenishmentTaskRepository, org.mockito.Mockito.never())
                .save(any());
    }

    /**
     * Odoo vector: the same inbound supply arriving BEYOND the horizon is excluded from
     * the horizon-bounded projection — trigger fires and the task replenishes to max off
     * the projection (max − projected, not max − onHand).
     */
    @Test
    void runBatchReplenishmentScan_inboundSupplyBeyondHorizon_triggersOnProjection() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-LATE-PO", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-LATE-PO", LOC_01, 1);
        // Horizon-bounded forecast: late PO excluded, but 3 units of due supply count →
        // projected 4 < min 5.
        when(forecastQuantityService.forecast(any(), any(), any(), any()))
                .thenReturn(new ForecastQuantityService.ForecastQuantities(
                        new BigDecimal("3"), new BigDecimal("0"), new BigDecimal("4")));
        when(replenishmentTaskRepository.findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                        any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(replenishmentTaskRepository
                        .existsByItemSKUAndDestinationLocationIdAndTriggerTypeAndCreatedAtGreaterThanEqual(
                                any(), any(), any(), any()))
                .thenReturn(false);

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(1, result.getPoliciesBelowMinimum());
        assertEquals(1, result.getTasksCreated());
        org.mockito.ArgumentCaptor<ReplenishmentTask> captor =
                org.mockito.ArgumentCaptor.forClass(ReplenishmentTask.class);
        org.mockito.Mockito.verify(replenishmentTaskRepository).save(captor.capture());
        assertEquals(16, captor.getValue().getQuantity()); // max 20 - projected 4 (NOT max - onHand 1 = 19)
    }

    /**
     * Horizon-boundary vector: {@code leadHorizon = now + leadTimeDays} exactly — the
     * resolved (feed) lead time of 5 days against the fixed clock must produce a horizon
     * of 2024-01-06T00:00:00Z, and the projection must be requested at that instant.
     */
    @Test
    void runBatchReplenishmentScan_passesResolvedLeadTimeHorizonToForecast() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-HORIZON", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-HORIZON", LOC_01, 9); // above min: trigger not fired, projection still requested
        when(leadTimeResolver.resolve(policy))
                .thenReturn(new LeadTimeResolver.ResolvedLeadTime(5, LeadTimeResolver.LeadTimeSource.FEED));

        replenishmentService.runBatchReplenishmentScan();

        org.mockito.ArgumentCaptor<Instant> horizonCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(forecastQuantityService)
                .forecast(
                        org.mockito.ArgumentMatchers.eq("SKU-HORIZON"),
                        org.mockito.ArgumentMatchers.eq(LOC_01),
                        horizonCaptor.capture(),
                        org.mockito.ArgumentMatchers.eq(new BigDecimal("9")));
        assertEquals(Instant.now(TEST_CLOCK).plus(Duration.ofDays(5)), horizonCaptor.getValue());
        assertEquals(Instant.parse("2024-01-06T00:00:00Z"), horizonCaptor.getValue());
    }

    /**
     * In-progress netting vector: refreshing the oldest open task nets the quantities of
     * OTHER open tasks for the same SKU/location (Odoo {@code qty_in_progress}) — the
     * refreshed task's own quantity is replaced, not double-counted.
     */
    @Test
    void runBatchReplenishmentScan_refreshNetsOtherOpenTaskQuantities() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-NET", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-NET", LOC_01, 2);
        ReplenishmentTask oldest = ReplenishmentTask.builder()
                .taskId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
                .itemSKU("SKU-NET")
                .quantity(5)
                .sourceLocationId(LOC_01)
                .destinationLocationId(LOC_01)
                .status(ReplenishmentStatus.PENDING)
                .triggerType(ReplenishmentTriggerType.BATCH)
                .build();
        ReplenishmentTask inProgress = ReplenishmentTask.builder()
                .taskId(UUID.fromString("00000000-0000-0000-0000-0000000000bb"))
                .itemSKU("SKU-NET")
                .quantity(4)
                .sourceLocationId(LOC_01)
                .destinationLocationId(LOC_01)
                .status(ReplenishmentStatus.IN_PROGRESS)
                .triggerType(ReplenishmentTriggerType.EVENT)
                .build();
        when(replenishmentTaskRepository.findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                        any(), any(), any()))
                .thenReturn(java.util.Optional.of(oldest));
        when(replenishmentTaskRepository.findByItemSKUAndDestinationLocationIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(oldest, inProgress));

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(1, result.getTasksRefreshed());
        assertEquals(0, result.getTasksCreated());
        // max 20 - projected 2 - other-in-progress 4 = 14 (own quantity 5 excluded).
        assertEquals(14, oldest.getQuantity());
        org.mockito.Mockito.verify(replenishmentTaskRepository).save(oldest);
    }

    /**
     * Zero-quantity vector: trigger fires (projected < min) but in-progress supply already
     * covers to max — no task is created. In-progress here flows through the F4 seam
     * (open supply not surfaced as the policy's own open task).
     */
    @Test
    void runBatchReplenishmentScan_triggeredButInProgressCoversToMax_createsNoTask() {
        ReplenishmentPolicy policy = policy(LOC_01, "SKU-COVERED", 5, 20);
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-COVERED", LOC_01, 3);
        when(replenishmentTaskRepository.findFirstByItemSKUAndDestinationLocationIdAndStatusInOrderByCreatedAtAsc(
                        any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
        // In-progress supply for the SKU/location already sums to 18: 20 - 3 - 18 < 0 → 0.
        when(replenishmentTaskRepository.findByItemSKUAndDestinationLocationIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(ReplenishmentTask.builder()
                        .taskId(UUID.fromString("00000000-0000-0000-0000-0000000000cc"))
                        .itemSKU("SKU-COVERED")
                        .quantity(18)
                        .status(ReplenishmentStatus.IN_PROGRESS)
                        .build()));

        ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();

        assertEquals(1, result.getPoliciesBelowMinimum());
        assertEquals(0, result.getTasksCreated());
        assertEquals(0, result.getTasksRefreshed());
        org.mockito.Mockito.verify(replenishmentTaskRepository, org.mockito.Mockito.never())
                .save(any());
    }

    // ─── getReplenishmentNeeds (odoo-parity F3/F6, issue #1041) ────

    @Test
    void getReplenishmentNeeds_skipsSuspendedPolicies() {
        ReplenishmentPolicy suspended = ReplenishmentPolicy.builder()
                .policyId(UUID.fromString("00000000-0000-0000-0000-0000000000d1"))
                .locationId(LOC_01)
                .itemSKU("SKU-SUSPENDED")
                .minimumQuantity(5)
                .maximumQuantity(20)
                .active(Boolean.FALSE)
                .build();
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(suspended));

        List<com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse> needs =
                replenishmentService.getReplenishmentNeeds();

        assertTrue(needs.isEmpty());
    }

    /**
     * Not-triggered vector: {@code projectedAvailable >= minimumQuantity} so the report reads
     * {@code wouldTrigger=false}, {@code suggestedQuantity=0} and no deadline — pinning that
     * {@link ReplenishmentServiceImpl#quantityToReplenish} and the deadline calculator are never
     * invoked for a policy that isn't below minimum. Also exercises the defensive
     * {@code maximumQuantity == null} fallback (response reads 0): reachable here because the
     * not-triggered branch skips the only code path that unboxes it.
     */
    @Test
    void getReplenishmentNeeds_notTriggered_reportsZeroQuantityAndNoDeadline() {
        ReplenishmentPolicy policy = ReplenishmentPolicy.builder()
                .policyId(UUID.fromString("00000000-0000-0000-0000-0000000000d2"))
                .locationId(LOC_01)
                .itemSKU("SKU-AT-MIN")
                .minimumQuantity(5)
                .maximumQuantity(null)
                .build();
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-AT-MIN", LOC_01, 5);

        List<com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse> needs =
                replenishmentService.getReplenishmentNeeds();

        assertEquals(1, needs.size());
        com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse need = needs.getFirst();
        assertEquals(false, need.isWouldTrigger());
        assertEquals(0, need.getSuggestedQuantity());
        assertEquals(0, need.getMaximumQuantity());
        assertNotNull(need.getPolicyId());
        assertEquals(null, need.getDeadlineDate());
        org.mockito.Mockito.verify(stockoutDeadlineCalculator, org.mockito.Mockito.never())
                .stockoutDeadline(any(), any(), any(), any(), any());
    }

    /** Triggered vector: below-minimum policy reports a positive suggested quantity and the stockout deadline. */
    @Test
    void getReplenishmentNeeds_triggered_reportsSuggestedQuantityAndDeadline() {
        ReplenishmentPolicy policy = ReplenishmentPolicy.builder()
                .policyId(UUID.fromString("00000000-0000-0000-0000-0000000000d3"))
                .locationId(LOC_01)
                .itemSKU("SKU-BELOW-MIN")
                .minimumQuantity(5)
                .maximumQuantity(20)
                .preferredSourceType(com.positivity.inventory.internal.enums.ReplenishmentSourceType.PURCHASE)
                .build();
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-BELOW-MIN", LOC_01, 3);
        java.time.LocalDate deadline = java.time.LocalDate.parse("2024-01-10");
        when(stockoutDeadlineCalculator.stockoutDeadline(any(), any(), any(), any(), any()))
                .thenReturn(deadline);

        List<com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse> needs =
                replenishmentService.getReplenishmentNeeds();

        assertEquals(1, needs.size());
        com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse need = needs.getFirst();
        assertTrue(need.isWouldTrigger());
        assertEquals(17, need.getSuggestedQuantity()); // max 20 - projected 3
        assertEquals("2024-01-10", need.getDeadlineDate());
        assertEquals("PURCHASE", need.getPreferredSourceType());
    }

    /**
     * Defensive-default vector: a policy read back with no persisted id and no preferred source
     * type still maps to a report row — {@code policyId=null} and
     * {@code preferredSourceType=EITHER}. {@code minimumQuantity} has no equivalent null vector:
     * {@link ReplenishmentServiceImpl#project} unconditionally unboxes it via
     * {@code BigDecimal.valueOf} before the report row is ever built, so that fallback branch in
     * the mapping code is unreachable from any policy this method can evaluate.
     */
    @Test
    void getReplenishmentNeeds_missingSourceType_fallsBackToEither() {
        // policyId is deliberately set: a policy reaching this method is repository-loaded, so
        // its @Id is never null, and the response declares policyId REQUIRED. Only the
        // preferredSourceType fallback is a reachable default (a row predating the column).
        UUID policyId = UUID.fromString("00000000-0000-0000-0000-00000000000d");
        ReplenishmentPolicy policy = ReplenishmentPolicy.builder()
                .policyId(policyId)
                .locationId(LOC_01)
                .itemSKU("SKU-NO-SRC")
                .minimumQuantity(5)
                .maximumQuantity(20)
                // Overrides the entity's own @Builder.Default (EITHER) to reach the
                // response-mapping fallback directly.
                .preferredSourceType(null)
                .build();
        when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));
        givenOnHand("SKU-NO-SRC", LOC_01, 5);

        List<com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse> needs =
                replenishmentService.getReplenishmentNeeds();

        assertEquals(1, needs.size());
        com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse need = needs.getFirst();
        assertEquals(policyId.toString(), need.getPolicyId());
        assertEquals("EITHER", need.getPreferredSourceType());
    }
}
