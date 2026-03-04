package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.CycleCountAdjustmentServiceImpl;
import com.positivity.security.common.GatewaySecurityConstants;

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
    private ApprovalThresholdEvaluator thresholdEvaluator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CycleCountAdjustmentServiceImpl service;

    private static final String ACTOR_USER_ID = "actor-person-id-001";
    private static final String ACTOR_USERNAME = "manager-user";
    private static final UUID STOCK_ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private Clock clock = Clock.systemDefaultZone();

    @BeforeEach
    void setUp() {
        service = new CycleCountAdjustmentServiceImpl(
                adjustmentRepository, ledgerRepository, thresholdEvaluator, eventPublisher, clock);
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
        UUID assignedId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();

        when(thresholdEvaluator.evaluateRequiredApprovalTier(any(CycleCountAdjustment.class)))
                .thenReturn(Optional.empty());
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> {
            CycleCountAdjustment adj = inv.getArgument(0);
            if (adj.getAdjustmentId() == null) {
                adj.setAdjustmentId(assignedId);
            }
            return adj;
        });
        when(ledgerRepository.calculateOnHandQuantity(STOCK_ITEM_ID)).thenReturn(10);
        when(ledgerRepository.save(any(InventoryLedgerEntry.class))).thenAnswer(inv -> {
            InventoryLedgerEntry entry = inv.getArgument(0);
            entry.setLedgerEntryId(ledgerId);
            return entry;
        });

        AdjustmentResponse response = service.createAdjustment(request);

        assertThat(response.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(response.getApprovedByUserId()).isEqualTo("SYSTEM");
        assertThat(response.getLedgerEntryId()).isEqualTo(ledgerId);
        verify(ledgerRepository).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void createAdjustment_aboveThreshold_setsPendingApproval() {
        // counted < onHand → variance, evaluator returns TIER_1_MANAGER →
        // PENDING_APPROVAL
        CreateAdjustmentRequest request = createRequest(5, 10);
        UUID assignedId = UUID.randomUUID();

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
        verify(ledgerRepository, never()).save(any());
        verify(ledgerRepository, never()).calculateOnHandQuantity(any(UUID.class));
    }

    // -------------------------------------------------------------------------
    // approveAdjustment
    // -------------------------------------------------------------------------

    @Test
    void approveAdjustment_pendingApproval_setsPostedAndCreatesLedgerEntry() {
        setUpAuthenticatedActor();
        UUID adjustmentId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        CycleCountAdjustment adjustment = pendingAdjustment(adjustmentId);

        when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepository.calculateOnHandQuantity(STOCK_ITEM_ID)).thenReturn(10);
        when(ledgerRepository.save(any(InventoryLedgerEntry.class))).thenAnswer(inv -> {
            InventoryLedgerEntry entry = inv.getArgument(0);
            entry.setLedgerEntryId(ledgerId);
            return entry;
        });

        ApproveAdjustmentRequest request = ApproveAdjustmentRequest.builder().notes("Looks good").build();
        AdjustmentResponse response = service.approveAdjustment(adjustmentId, request, "corr-id-001");

        assertThat(response.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(response.getApprovedByUserId()).isEqualTo(ACTOR_USERNAME);
        verify(ledgerRepository).save(any(InventoryLedgerEntry.class));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void approveAdjustment_nonExistentAdjustment_throwsIllegalArgumentException() {
        setUpAuthenticatedActor();
        UUID adjustmentId = UUID.randomUUID();

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
        UUID adjustmentId = UUID.randomUUID();
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
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void rejectAdjustment_nonExistentAdjustment_throwsIllegalArgumentException() {
        UUID adjustmentId = UUID.randomUUID();

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
        UUID id = UUID.randomUUID();
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
        UUID id = UUID.randomUUID();
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
                .countedQuantity(counted)
                .quantityOnHandBefore(onHand)
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
                .quantityChange(-2)
                .costAtTimeOfAdjustment(BigDecimal.valueOf(50.00))
                .quantityOnHandBefore(10)
                .countedQuantity(8)
                .createdByUserId("counter-user-1")
                .status(AdjustmentStatus.PENDING_APPROVAL)
                .build();
    }

    /**
     * Populates the Spring {@link SecurityContextHolder} with a
     * {@link TestingAuthenticationToken} carrying the gateway-injected user-id
     * detail required by
     * {@link com.positivity.security.common.SecurityContextHelper}.
     */
    private void setUpAuthenticatedActor() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                ACTOR_USERNAME, "password", "ROLE_MANAGER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID, ACTOR_USER_ID,
                GatewaySecurityConstants.DETAIL_USERNAME, ACTOR_USERNAME));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
