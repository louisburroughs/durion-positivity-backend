package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.ApprovalThresholdEvaluator;
import com.positivity.security.common.GatewaySecurityConstants;

@ExtendWith(MockitoExtension.class)
class CycleCountAdjustmentServiceImplTest {
    private static final String ACTOR_USER_ID = "actor-person-id-001";
    private static final String ACTOR_USERNAME = "manager-user";


    @Mock
    private CycleCountAdjustmentRepository adjustmentRepository;
    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;
    @Mock
    private ApprovalThresholdEvaluator thresholdEvaluator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CycleCountAdjustmentServiceImpl service;
    private Clock fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new CycleCountAdjustmentServiceImpl(
                adjustmentRepository,
                ledgerRepository,
                thresholdEvaluator,
                eventPublisher,
                fixedClock);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("createAdjustment")
    class CreateAdjustment {

        @Test
        @DisplayName("should throw IllegalArgumentException if quantity change is zero")
        void shouldThrowExceptionWhenQuantityIsUnchanged() {
            CreateAdjustmentRequest request = CreateAdjustmentRequest.builder()
                    .countedQuantity(10)
                    .quantityOnHandBefore(10)
                    .build();

            assertThatThrownBy(() -> service.createAdjustment(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No adjustment needed - counted quantity matches system quantity");
        }

        @Test
        @DisplayName("should create adjustment with PENDING_APPROVAL status when approval is required")
        void shouldCreatePendingAdjustmentWhenApprovalRequired() {
            CreateAdjustmentRequest request = CreateAdjustmentRequest.builder()
                    .stockItemId(UUID.randomUUID())
                    .countedQuantity(15)
                    .quantityOnHandBefore(10)
                    .costAtTimeOfAdjustment(BigDecimal.TEN)
                    .build();

            when(thresholdEvaluator.evaluateRequiredApprovalTier(any(CycleCountAdjustment.class)))
                    .thenReturn(Optional.of(ApprovalTier.TIER_1_MANAGER));

            when(adjustmentRepository.save(any(CycleCountAdjustment.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.createAdjustment(request);

            ArgumentCaptor<CycleCountAdjustment> captor = ArgumentCaptor.forClass(CycleCountAdjustment.class);
            verify(adjustmentRepository).save(captor.capture());
            CycleCountAdjustment savedAdjustment = captor.getValue();

            assertThat(savedAdjustment.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
            assertThat(savedAdjustment.getRequiredApprovalTier()).isEqualTo(ApprovalTier.TIER_1_MANAGER);
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("should create AUTO_APPROVED adjustment and post to ledger when no approval is required")
        void shouldAutoApproveAdjustmentWhenNoApprovalRequired() {
            UUID stockItemId = UUID.randomUUID();
            CreateAdjustmentRequest request = CreateAdjustmentRequest.builder()
                    .stockItemId(stockItemId)
                    .countedQuantity(11)
                    .quantityOnHandBefore(10)
                    .costAtTimeOfAdjustment(BigDecimal.ONE)
                    .build();

            when(thresholdEvaluator.evaluateRequiredApprovalTier(any(CycleCountAdjustment.class)))
                    .thenReturn(Optional.empty());

            when(adjustmentRepository.save(any(CycleCountAdjustment.class)))
                    .thenAnswer(invocation -> {
                        CycleCountAdjustment saved = invocation.getArgument(0);
                        if (saved.getAdjustmentId() == null) {
                            saved.setAdjustmentId(UUID.randomUUID());
                        }
                        return saved;
                    });

            when(ledgerRepository.calculateOnHandQuantity(stockItemId)).thenReturn(10);
            when(ledgerRepository.save(any(InventoryLedgerEntry.class)))
                    .thenAnswer(invocation -> {
                        InventoryLedgerEntry entry = invocation.getArgument(0);
                        entry.setLedgerEntryId(UUID.randomUUID());
                        return entry;
                    });

            service.createAdjustment(request);

            ArgumentCaptor<CycleCountAdjustment> adjustmentCaptor = ArgumentCaptor.forClass(CycleCountAdjustment.class);
            verify(adjustmentRepository, org.mockito.Mockito.times(2)).save(adjustmentCaptor.capture());

            verify(ledgerRepository).save(any(InventoryLedgerEntry.class));

            CycleCountAdjustment finalSave = adjustmentCaptor.getAllValues().get(1);

            assertThat(finalSave.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
            assertThat(finalSave.getApprovedByUserId()).isEqualTo("SYSTEM");
            assertThat(finalSave.getLedgerEntryId()).isNotNull();
        }
    }

    @Nested
    @DisplayName("approveAdjustment")
    class ApproveAdjustment {

        private UUID adjustmentId;
        private ApproveAdjustmentRequest request;

        @BeforeEach
        void setUp() {
            adjustmentId = UUID.randomUUID();
            request = ApproveAdjustmentRequest.builder()
                    .notes("Test notes")
                    .build();
            setUpAuthenticatedActor();
        }

        @Test
        @DisplayName("should throw IllegalArgumentException if adjustment is not found")
        void shouldThrowExceptionWhenAdjustmentNotFound() {
            when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approveAdjustment(adjustmentId, request, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Adjustment not found: " + adjustmentId);
        }

        @Test
        @DisplayName("should throw IllegalStateException if adjustment is not pending approval")
        void shouldThrowExceptionWhenAdjustmentNotPending() {
            CycleCountAdjustment adjustment = new CycleCountAdjustment();
            adjustment.setStatus(AdjustmentStatus.APPROVED);
            when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));

            assertThatThrownBy(() -> service.approveAdjustment(adjustmentId, request, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot approve adjustment in status: " + AdjustmentStatus.APPROVED);
        }
    }

    @Nested
    @DisplayName("rejectAdjustment")
    class RejectAdjustment {
        private UUID adjustmentId;
        private com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest request;

        @BeforeEach
        void setUp() {
            adjustmentId = UUID.randomUUID();
            request = new com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest("reject-user-id",
                    "Test rejection");
        }

        @Test
        @DisplayName("should throw IllegalArgumentException if adjustment is not found")
        void shouldThrowExceptionWhenAdjustmentNotFound() {
            when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rejectAdjustment(adjustmentId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Adjustment not found: " + adjustmentId);
        }

        @Test
        @DisplayName("should throw IllegalStateException if adjustment is not pending approval")
        void shouldThrowExceptionWhenAdjustmentNotPending() {
            CycleCountAdjustment adjustment = new CycleCountAdjustment();
            adjustment.setStatus(AdjustmentStatus.REJECTED);
            when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));

            assertThatThrownBy(() -> service.rejectAdjustment(adjustmentId, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot reject adjustment in status: " + AdjustmentStatus.REJECTED);
        }

        @Test
        @DisplayName("should reject a pending adjustment")
        void shouldRejectPendingAdjustment() {
            CycleCountAdjustment adjustment = CycleCountAdjustment.builder()
                    .adjustmentId(adjustmentId)
                    .stockItemId(UUID.randomUUID())
                    .reasonCode("CYCLE_COUNT_SHRINK")
                    .quantityChange(-2)
                    .costAtTimeOfAdjustment(BigDecimal.ONE)
                    .quantityOnHandBefore(10)
                    .countedQuantity(8)
                    .createdByUserId("counter-user")
                    .status(AdjustmentStatus.PENDING_APPROVAL)
                    .build();

            when(adjustmentRepository.findById(adjustmentId)).thenReturn(Optional.of(adjustment));
            when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));

            service.rejectAdjustment(adjustmentId, request);

            ArgumentCaptor<CycleCountAdjustment> captor = ArgumentCaptor.forClass(CycleCountAdjustment.class);
            verify(adjustmentRepository).save(captor.capture());
            CycleCountAdjustment saved = captor.getValue();

            assertThat(saved.getStatus()).isEqualTo(AdjustmentStatus.REJECTED);
            assertThat(saved.getRejectedByUserId()).isEqualTo("reject-user-id");
            assertThat(saved.getRejectionReason()).isEqualTo("Test rejection");
            assertThat(saved.getRejectedAt()).isNotNull();
        }
    }

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
