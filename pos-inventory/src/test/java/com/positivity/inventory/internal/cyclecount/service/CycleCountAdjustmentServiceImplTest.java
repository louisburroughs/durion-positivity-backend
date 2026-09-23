package com.positivity.inventory.internal.cyclecount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.exception.AdjustmentLedgerPostingException;
import com.positivity.inventory.internal.exception.NegativeStockPolicyViolationException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.ApprovalThresholdEvaluator;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for CycleCountAdjustmentServiceImpl.
 *
 * <p>
 * Covers adjustment lifecycle: create (zero variance, auto-approve, pending
 * approval),
 * approve, reject, list, and get operations.
 *
 * Issue: #26
 */
@ExtendWith(MockitoExtension.class)
class CycleCountAdjustmentServiceImplTest {

    @Mock
    private CycleCountAdjustmentRepository adjustmentRepository;

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    @Mock
    private LedgerPostingService ledgerPostingService;

    @Mock
    private ApprovalThresholdEvaluator thresholdEvaluator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private com.positivity.inventory.internal.repository.CycleCountTaskRepository taskRepository;

    @Mock
    private com.positivity.inventory.internal.service.CycleCountConflictDetector conflictDetector;

    @Mock
    private com.positivity.inventory.internal.repository.SkuCostStateRepository costStateRepository;

    @Mock
    private com.positivity.inventory.internal.service.CostingMethodResolver methodResolver;

    @Mock
    private com.positivity.inventory.internal.service.BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    @Mock
    private com.positivity.inventory.internal.service.LocationScopeService locationScopeService;

    private CycleCountAdjustmentServiceImpl service;

    private static final String ACTOR_USER_ID = "actor-person-id-001";
    private static final String ACTOR_USERNAME = "manager-user";
    private static final String STOCK_ITEM_ID = "SKU-ADJ-TEST-001";
    private Clock clock = Clock.systemDefaultZone();

    @BeforeEach
    void setUp() {
        service = new CycleCountAdjustmentServiceImpl(
                adjustmentRepository,
                ledgerRepository,
                ledgerPostingService,
                thresholdEvaluator,
                eventPublisher,
                clock,
                taskRepository,
                conflictDetector,
                costStateRepository,
                methodResolver,
                baseUnitOfMeasureResolver,
                locationScopeService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // createAdjustment
    // -------------------------------------------------------------------------

    @Test
    void createAdjustment_zeroVariance_throwsIllegalArgumentException() {
        // counted == onHand → quantityChange == 0
        CreateAdjustmentRequest request = createRequest(10, 10);

        assertThatThrownBy(() -> service.createAdjustment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No adjustment needed");

        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void createAdjustment_belowThreshold_autoApprovesAndPosts() {
        // counted < onHand → negative variance, evaluator returns empty → AUTO_APPROVED
        CreateAdjustmentRequest request = createRequest(8, 10);
        UUID assignedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID ledgerId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(thresholdEvaluator.evaluateRequiredApprovalTier(any(CycleCountAdjustment.class)))
                .thenReturn(Optional.empty());
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> {
            CycleCountAdjustment adj = inv.getArgument(0);
            if (adj.getAdjustmentId() == null) {
                adj.setAdjustmentId(assignedId);
            }
            return adj;
        });
        when(ledgerRepository.calculateOnHandQuantity(STOCK_ITEM_ID)).thenReturn(new BigDecimal("10"));
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class))).thenAnswer(inv -> {
            InventoryLedgerEntry entry = inv.getArgument(0);
            entry.setLedgerEntryId(ledgerId);
            return entry;
        });

        AdjustmentResponse response = service.createAdjustment(request);

        assertThat(response.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(response.getApprovedByUserId()).isEqualTo("SYSTEM");
        assertThat(response.getLedgerEntryId()).isEqualTo(ledgerId);
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void createAdjustment_aboveThreshold_setsPendingApproval() {
        // counted < onHand → variance, evaluator returns TIER_1_MANAGER →
        // PENDING_APPROVAL
        CreateAdjustmentRequest request = createRequest(5, 10);
        UUID assignedId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(thresholdEvaluator.evaluateRequiredApprovalTier(any(CycleCountAdjustment.class)))
                .thenReturn(Optional.of(ApprovalTier.TIER_1_MANAGER));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> {
            CycleCountAdjustment adj = inv.getArgument(0);
            adj.setAdjustmentId(assignedId);
            return adj;
        });

        AdjustmentResponse response = service.createAdjustment(request);

        assertThat(response.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
        assertThat(response.getRequiredApprovalTier()).isEqualTo(ApprovalTier.TIER_1_MANAGER);
        verify(ledgerPostingService, never()).post(any());
        verify(ledgerRepository, never()).calculateOnHandQuantity(any(UUID.class));
    }

    // -------------------------------------------------------------------------
    // approveAdjustment
    // -------------------------------------------------------------------------

    @Test
    void approveAdjustment_pendingApproval_setsPostedAndCreatesLedgerEntry() {
        setUpAuthenticatedActor();
        UUID adjustmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID ledgerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountAdjustment adjustment = pendingAdjustment(adjustmentId);

        when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepository.calculateOnHandQuantity(STOCK_ITEM_ID)).thenReturn(new BigDecimal("10"));
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class))).thenAnswer(inv -> {
            InventoryLedgerEntry entry = inv.getArgument(0);
            entry.setLedgerEntryId(ledgerId);
            return entry;
        });

        ApproveAdjustmentRequest request =
                ApproveAdjustmentRequest.builder().notes("Looks good").build();
        AdjustmentResponse response = service.approveAdjustment(adjustmentId, request, "corr-id-001");

        assertThat(response.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(response.getApprovedByUserId()).isEqualTo(ACTOR_USERNAME);
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void approveAdjustment_nonExistentAdjustment_throwsIllegalArgumentException() {
        setUpAuthenticatedActor();
        UUID adjustmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.empty());

        ApproveAdjustmentRequest request = ApproveAdjustmentRequest.builder().build();

        assertThatThrownBy(() -> service.approveAdjustment(adjustmentId, request, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(adjustmentId.toString());
    }

    // -------------------------------------------------------------------------
    // rejectAdjustment
    // -------------------------------------------------------------------------

    @Test
    void rejectAdjustment_pendingApproval_setsRejectedStatus() {
        UUID adjustmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountAdjustment adjustment = pendingAdjustment(adjustmentId);

        when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));

        RejectAdjustmentRequest request = RejectAdjustmentRequest.builder()
                .rejectorUserId("mgr-001")
                .rejectionReason("Count value seems incorrect")
                .build();

        AdjustmentResponse response = service.rejectAdjustment(adjustmentId, request);

        assertThat(response.getStatus()).isEqualTo(AdjustmentStatus.REJECTED);
        assertThat(response.getRejectedByUserId()).isEqualTo("mgr-001");
        assertThat(response.getRejectionReason()).isEqualTo("Count value seems incorrect");
        verify(ledgerPostingService, never()).post(any());
    }

    @Test
    void approveAdjustment_negativeStockPolicyRejection_surfacesPolicyViolationNotPostingFailure() {
        // #2167: a count the ledger refuses (would drive on-hand negative) must reach the handler as
        // the 422 policy violation, not be wrapped into ADJUSTMENT_LEDGER_POST_FAILED / 500.
        setUpAuthenticatedActor();
        UUID adjustmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountAdjustment adjustment = pendingAdjustment(adjustmentId);
        NegativeStockPolicyViolationException rejection = new NegativeStockPolicyViolationException(
                NegativeStockPolicyViolationException.FLOOR_VIOLATION, "would take on-hand to -3.0000");

        when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepository.calculateOnHandQuantity(STOCK_ITEM_ID)).thenReturn(new BigDecimal("1"));
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class))).thenThrow(rejection);

        ApproveAdjustmentRequest request = ApproveAdjustmentRequest.builder().build();

        assertThatThrownBy(() -> service.approveAdjustment(adjustmentId, request, "corr-id-001"))
                .isSameAs(rejection);
        assertThat(adjustment.getStatus()).isNotEqualTo(AdjustmentStatus.FAILED);
        assertThat(adjustment.getErrorMessage()).isNull();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void approveAdjustment_unexpectedPostingFailure_stillWrapsAsLedgerPostingException() {
        setUpAuthenticatedActor();
        UUID adjustmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountAdjustment adjustment = pendingAdjustment(adjustmentId);

        when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepository.calculateOnHandQuantity(STOCK_ITEM_ID)).thenReturn(new BigDecimal("10"));
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class)))
                .thenThrow(new IllegalStateException("summary row lock timed out"));

        ApproveAdjustmentRequest request = ApproveAdjustmentRequest.builder().build();

        assertThatThrownBy(() -> service.approveAdjustment(adjustmentId, request, "corr-id-001"))
                .isInstanceOf(AdjustmentLedgerPostingException.class);
        assertThat(adjustment.getStatus()).isEqualTo(AdjustmentStatus.FAILED);
    }

    @Test
    void createAdjustment_withoutTask_postsAgainstRequestedLocation() {
        // #2167: a task-less adjustment naming a location posts against that shelf, not the
        // stock item's location-less balance.
        UUID locationId = UUID.fromString("01960003-0000-7000-8000-000000000012");
        CreateAdjustmentRequest request = createRequest(8, 10);
        request.setLocationId(locationId);

        when(thresholdEvaluator.evaluateRequiredApprovalTier(any(CycleCountAdjustment.class)))
                .thenReturn(Optional.empty());
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(STOCK_ITEM_ID, locationId))
                .thenReturn(new BigDecimal("10"));
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        AdjustmentResponse response = service.createAdjustment(request);

        ArgumentCaptor<InventoryLedgerEntry> posted = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(posted.capture());
        assertThat(posted.getValue().getLocationId()).isEqualTo(locationId);
        assertThat(posted.getValue().getQuantityAfter()).isEqualByComparingTo("8");
        assertThat(response.getLocationId()).isEqualTo(locationId);
        verify(ledgerRepository, never()).calculateOnHandQuantity(STOCK_ITEM_ID);
    }

    @Test
    void createAdjustment_resolvedLocation_isGatedByCallerLocationScope() {
        // #2167 (ADR-0061 gate): a caller-named shelf outside the caller's reach is refused
        // before anything is recorded.
        UUID locationId = UUID.fromString("01960003-0000-7000-8000-000000000012");
        CreateAdjustmentRequest request = createRequest(5, 10);
        request.setLocationId(locationId);
        LocationScopeDeniedException denied =
                new LocationScopeDeniedException("inventory:adjustment:create", locationId.toString());
        doThrow(denied)
                .when(locationScopeService)
                .require(
                        locationId,
                        com.positivity.inventory.internal.security.InventoryPermissionRegistry.ADJUSTMENT_CREATE);

        assertThatThrownBy(() -> service.createAdjustment(request)).isSameAs(denied);
        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void createAdjustment_withoutLocation_skipsScopeGate() {
        CreateAdjustmentRequest request = createRequest(5, 10);
        when(thresholdEvaluator.evaluateRequiredApprovalTier(any(CycleCountAdjustment.class)))
                .thenReturn(Optional.of(ApprovalTier.TIER_1_MANAGER));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createAdjustment(request);

        verifyNoInteractions(locationScopeService);
    }

    @Test
    void approveAdjustment_conflictTaskWithFreeTextBin_recomputesAgainstPostingLocation() {
        // #2167 review: a free-text bin resolves no location from the task, so the stored request
        // location is what posts. The CONFLICT recompute must read on-hand at that same location,
        // not the SKU-wide total, or the delta is computed over one scope and posted to another.
        setUpAuthenticatedActor();
        UUID adjustmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID taskId = UUID.fromString("01960003-0000-7000-8000-000000000009");
        UUID locationId = UUID.fromString("01960003-0000-7000-8000-000000000012");
        CycleCountAdjustment adjustment = pendingAdjustment(adjustmentId);
        adjustment.setTaskId(taskId);
        adjustment.setLocationId(locationId);
        CycleCountTask task = task(taskId, "AISLE-3-SHELF-B");
        task.setStatus(TaskStatus.CONFLICT);

        when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(STOCK_ITEM_ID, locationId))
                .thenReturn(new BigDecimal("9"));
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        service.approveAdjustment(
                adjustmentId, ApproveAdjustmentRequest.builder().build(), "corr-id-001");

        // counted 8 against 9 on that shelf → -1, posted at the same shelf.
        assertThat(adjustment.getQuantityChange()).isEqualByComparingTo("-1");
        ArgumentCaptor<InventoryLedgerEntry> posted = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(posted.capture());
        assertThat(posted.getValue().getLocationId()).isEqualTo(locationId);
        assertThat(posted.getValue().getQuantityAfter()).isEqualByComparingTo("8");
        verify(ledgerRepository, never()).calculateOnHandQuantity(STOCK_ITEM_ID);
    }

    @Test
    void createAdjustment_taskBinLocation_isRecordedWhenRequestOmitsLocation() {
        UUID taskId = UUID.fromString("01960003-0000-7000-8000-000000000009");
        UUID binLocation = UUID.fromString("01960003-0000-7000-8000-000000000012");
        CreateAdjustmentRequest request = createRequest(5, 10);
        request.setTaskId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(taskId, binLocation.toString())));
        when(thresholdEvaluator.evaluateRequiredApprovalTier(any(CycleCountAdjustment.class)))
                .thenReturn(Optional.of(ApprovalTier.TIER_1_MANAGER));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));

        AdjustmentResponse response = service.createAdjustment(request);

        assertThat(response.getLocationId()).isEqualTo(binLocation);
    }

    @Test
    void createAdjustment_requestLocationContradictingTaskBin_isRejected() {
        UUID taskId = UUID.fromString("01960003-0000-7000-8000-000000000009");
        CreateAdjustmentRequest request = createRequest(5, 10);
        request.setTaskId(taskId);
        request.setLocationId(UUID.fromString("01960003-0000-7000-8000-000000000099"));

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task(taskId, "01960003-0000-7000-8000-000000000012")));

        assertThatThrownBy(() -> service.createAdjustment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match task");
        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void createAdjustment_unknownTask_throwsTaskNotFound() {
        UUID taskId = UUID.fromString("01960003-0000-7000-8000-000000000009");
        CreateAdjustmentRequest request = createRequest(5, 10);
        request.setTaskId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAdjustment(request)).isInstanceOf(TaskNotFoundException.class);
        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void rejectAdjustment_nonExistentAdjustment_throwsIllegalArgumentException() {
        UUID adjustmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.empty());

        RejectAdjustmentRequest request = RejectAdjustmentRequest.builder()
                .rejectorUserId("mgr-001")
                .rejectionReason("reason")
                .build();

        assertThatThrownBy(() -> service.rejectAdjustment(adjustmentId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(adjustmentId.toString());
    }

    // -------------------------------------------------------------------------
    // listAdjustmentsByStatus / getAdjustment
    // -------------------------------------------------------------------------

    @Test
    void listAdjustmentsByStatus_returnsList() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountAdjustment adjustment = pendingAdjustment(id);

        when(adjustmentRepository.findByStatus(AdjustmentStatus.PENDING_APPROVAL))
                .thenReturn(List.of(adjustment));

        List<AdjustmentResponse> list = service.listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getAdjustmentId()).isEqualTo(id);
        assertThat(list.get(0).getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
    }

    @Test
    void getAdjustment_existing_returnsResponse() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountAdjustment adjustment = pendingAdjustment(id);

        when(adjustmentRepository.findById(id)).thenReturn(Optional.of(adjustment));

        AdjustmentResponse response = service.getAdjustment(id);

        assertThat(response.getAdjustmentId()).isEqualTo(id);
        assertThat(response.getStockItemId()).isEqualTo(STOCK_ITEM_ID);
        assertThat(response.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal {@link CreateAdjustmentRequest} with the
     * given counted and on-hand quantities.
     */
    private CreateAdjustmentRequest createRequest(int counted, int onHand) {
        return CreateAdjustmentRequest.builder()
                .stockItemId(STOCK_ITEM_ID)
                .reasonCode("CYCLE_COUNT_SHRINK")
                .countedQuantity(BigDecimal.valueOf(counted))
                .quantityOnHandBefore(BigDecimal.valueOf(onHand))
                .costAtTimeOfAdjustment(BigDecimal.valueOf(50.00))
                .createdByUserId("counter-user-1")
                .build();
    }

    /**
     * Builds a {@link CycleCountAdjustment} in PENDING_APPROVAL state with a
     * negative quantity change of -2 (counted=8 vs onHand=10).
     */
    private CycleCountAdjustment pendingAdjustment(UUID id) {
        return CycleCountAdjustment.builder()
                .adjustmentId(id)
                .stockItemId(STOCK_ITEM_ID)
                .reasonCode("CYCLE_COUNT_SHRINK")
                .quantityChange(new BigDecimal("-2"))
                .costAtTimeOfAdjustment(BigDecimal.valueOf(50.00))
                .quantityOnHandBefore(new BigDecimal("10"))
                .countedQuantity(new BigDecimal("8"))
                .createdByUserId("counter-user-1")
                .status(AdjustmentStatus.PENDING_APPROVAL)
                .build();
    }

    private CycleCountTask task(UUID taskId, String binLocation) {
        return CycleCountTask.builder()
                .taskId(taskId)
                .binLocation(binLocation)
                .itemSku(STOCK_ITEM_ID)
                .expectedQuantity(new BigDecimal("10"))
                .auditorId("counter-user-1")
                .status(TaskStatus.COUNTED_PENDING_REVIEW)
                .build();
    }

    /**
     * Populates the Spring {@link SecurityContextHolder} with a
     * {@link TestingAuthenticationToken} carrying the gateway-injected user-id
     * detail required by
     * {@link com.positivity.security.common.SecurityContextHelper}.
     */
    private void setUpAuthenticatedActor() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken(ACTOR_USERNAME, "password", "ROLE_MANAGER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID, ACTOR_USER_ID,
                GatewaySecurityConstants.DETAIL_USERNAME, ACTOR_USERNAME));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
