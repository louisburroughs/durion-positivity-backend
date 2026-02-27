package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.service.ApprovalThresholdEvaluator;
import java.math.BigDecimal;
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
import org.springframework.context.ApplicationEventPublisher;

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

    @BeforeEach
    void setUp() {
        service = new CycleCountAdjustmentServiceImpl(
            adjustmentRepository,
            ledgerRepository,
            thresholdEvaluator,
            eventPublisher
        );
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
                .thenReturn(Optional.of(ApprovalTier.MANAGER_APPROVAL));

            when(adjustmentRepository.save(any(CycleCountAdjustment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            service.createAdjustment(request);

            ArgumentCaptor<CycleCountAdjustment> captor = ArgumentCaptor.forClass(CycleCountAdjustment.class);
            verify(adjustmentRepository).save(captor.capture());
            CycleCountAdjustment savedAdjustment = captor.getValue();

            assertThat(savedAdjustment.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
            assertThat(savedAdjustment.getRequiredApprovalTier()).isEqualTo(ApprovalTier.MANAGER_APPROVAL);
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

            CycleCountAdjustment adjustment = CycleCountAdjustment.builder()
                .adjustmentId(UUID.randomUUID())
                .stockItemId(stockItemId)
                .quantityChange(1)
                .build();

            when(thresholdEvaluator.evaluateRequiredApprovalTier(any(CycleCountAdjustment.class)))
                .thenReturn(Optional.empty());

            when(adjustmentRepository.save(any(CycleCountAdjustment.class)))
                .thenReturn(adjustment) // first save
                .thenAnswer(invocation -> invocation.getArgument(0)); // second save

            when(ledgerRepository.calculateOnHandQuantity(stockItemId)).thenReturn(10);
            when(ledgerRepository.save(any(InventoryLedgerEntry.class)))
                .thenAnswer(invocation -> {
                    InventoryLedgerEntry entry = invocation.getArgument(0);
                    entry.setLedgerEntryId(UUID.randomUUID());
                    return entry;
                });

            service.createAdjustment(request);

            ArgumentCaptor<CycleCountAdjustment> adjustmentCaptor = ArgumentCaptor.forClass(CycleCountAdjustment.class);
            verify(adjustmentRepository).save(adjustmentCaptor.capture());
            CycleCountAdjustment initialSave = adjustmentCaptor.getValue();

            assertThat(initialSave.getStatus()).isEqualTo(AdjustmentStatus.AUTO_APPROVED);
            assertThat(initialSave.getApprovedByUserId()).isEqualTo("SYSTEM");

            verify(ledgerRepository).save(any(InventoryLedgerEntry.class));

            ArgumentCaptor<CycleCountAdjustment> finalSaveCaptor = ArgumentCaptor.forClass(CycleCountAdjustment.class);
            // there are two saves, we want to check the second one
            verify(adjustmentRepository, org.mockito.Mockito.times(2)).save(finalSaveCaptor.capture());
            CycleCountAdjustment finalSave = finalSaveCaptor.getAllValues().get(1);

            assertThat(finalSave.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
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
            request = new ApproveAdjustmentRequest("Test notes");
            // Mock security context
            // Note: In a real test, you'd use a testing utility for this.
            // For simplicity here, we assume the context is handled elsewhere or not strictly checked.
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
            request = new com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest("reject-user-id", "Test rejection");
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
            CycleCountAdjustment adjustment = new CycleCountAdjustment();
            adjustment.setStatus(AdjustmentStatus.PENDING_APPROVAL);

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
}
