package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.dto.RecordMovementRequest;
import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.entity.InventoryAdjustmentRequest;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.AdjustmentRequestStatus;
import com.positivity.inventory.internal.enums.MovementType;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.repository.InventoryAdjustmentRequestRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.StockMovementServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    @Mock
    private InventoryAdjustmentRequestRepository adjustmentRepository;

    @InjectMocks
    private StockMovementServiceImpl service;

    @Test
    void recordMovement_receive_setsGoodsReceipt_positiveDelta_andSavesEntry() {
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.RECEIVE, 5);
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(10);

        InventoryLedgerEntry result = service.recordMovement(request, "actor-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_RECEIPT);
        assertThat(result.getChangeInQuantity()).isEqualTo(5);
        verify(ledgerRepository).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_pick_withSufficientStock_setsGoodsIssue_negativeDelta() {
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.PICK, 4);
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(10, 10);

        InventoryLedgerEntry result = service.recordMovement(request, "actor-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_ISSUE);
        assertThat(result.getChangeInQuantity()).isEqualTo(-4);
        verify(ledgerRepository).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_pick_withInsufficientStock_throwsInsufficientStockException() {
        RecordMovementRequest request = baseMovementRequest(MovementType.PICK, 5);
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(3);

        Throwable exception = catchThrowable(() -> service.recordMovement(request, "actor-1"));

        assertThat(exception).isInstanceOf(InsufficientStockException.class);
        verify(ledgerRepository, never()).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_issue_withInsufficientStock_throwsInsufficientStockException() {
        RecordMovementRequest request = baseMovementRequest(MovementType.ISSUE, 7);
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(2);

        Throwable exception = catchThrowable(() -> service.recordMovement(request, "actor-1"));

        assertThat(exception).isInstanceOf(InsufficientStockException.class);
        verify(ledgerRepository, never()).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_transfer_savesTransferInAndTransferOut_withLocations() {
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.TRANSFER, 6);
        request.setToLocationId("LOC-2");
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(20, 20);

        service.recordMovement(request, "actor-1");

        ArgumentCaptor<InventoryLedgerEntry> captor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerRepository, times(2)).save(captor.capture());
        List<InventoryLedgerEntry> savedEntries = captor.getAllValues();

        InventoryLedgerEntry transferIn = savedEntries.get(0);
        assertThat(transferIn.getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_IN);
        assertThat(transferIn.getLocationId()).isEqualTo("LOC-2");
        assertThat(transferIn.getFromLocationId()).isEqualTo("LOC-1");
        assertThat(transferIn.getToLocationId()).isEqualTo("LOC-2");
        assertThat(transferIn.getChangeInQuantity()).isEqualTo(6);

        InventoryLedgerEntry transferOut = savedEntries.get(1);
        assertThat(transferOut.getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_OUT);
        assertThat(transferOut.getLocationId()).isEqualTo("LOC-1");
        assertThat(transferOut.getFromLocationId()).isEqualTo("LOC-1");
        assertThat(transferOut.getToLocationId()).isEqualTo("LOC-2");
        assertThat(transferOut.getChangeInQuantity()).isEqualTo(-6);
    }

    @Test
    void recordMovement_putAway_setsPutaway_positiveDelta() {
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.PUT_AWAY, 3);
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(8);

        InventoryLedgerEntry result = service.recordMovement(request, "actor-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.PUTAWAY);
        assertThat(result.getChangeInQuantity()).isEqualTo(3);
        verify(ledgerRepository).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_return_setsReturnToStock_positiveDelta() {
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.RETURN, 2);
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(5);

        InventoryLedgerEntry result = service.recordMovement(request, "actor-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.RETURN_TO_STOCK);
        assertThat(result.getChangeInQuantity()).isEqualTo(2);
        verify(ledgerRepository).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_storesActorUserIdInTransactionUserId() {
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.RECEIVE, 1);
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(0);

        service.recordMovement(request, "actor-xyz");

        ArgumentCaptor<InventoryLedgerEntry> captor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerRepository).save(captor.capture());
        assertThat(captor.getValue().getTransactionUserId()).isEqualTo("actor-xyz");
    }

    @Test
    void createAdjustmentRequest_happyPath_savesPendingRequest_withRequestedByActor() {
        stubAdjustmentSaveReturnsRequest();
        CreateAdjustmentRequestDto request = baseAdjustmentRequest(7);

        AdjustmentRequestResponse result = service.createAdjustmentRequest(request, "requestor-1");

        assertThat(result.getStatus()).isEqualTo(AdjustmentRequestStatus.PENDING.name());

        ArgumentCaptor<InventoryAdjustmentRequest> captor = ArgumentCaptor.forClass(InventoryAdjustmentRequest.class);
        verify(adjustmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AdjustmentRequestStatus.PENDING);
        assertThat(captor.getValue().getRequestedByUserId()).isEqualTo("requestor-1");
    }

    @Test
    void createAdjustmentRequest_returnsRequestMatchingInputFields() {
        stubAdjustmentSaveReturnsRequest();
        CreateAdjustmentRequestDto request = baseAdjustmentRequest(11);

        AdjustmentRequestResponse result = service.createAdjustmentRequest(request, "requestor-1");

        assertThat(result.getProductSku()).isEqualTo("SKU-123");
        assertThat(result.getLocationId()).isEqualTo("LOC-1");
        assertThat(result.getQuantity()).isEqualTo(11);
        assertThat(result.getReasonCode()).isEqualTo("CYCLE_COUNT");
    }

    @Test
    void approveAdjustmentRequest_positiveQuantity_savesAdjustmentInLedgerEntry() {
        stubLedgerSaveReturnsEntry();
        stubAdjustmentSaveReturnsRequest();
        InventoryAdjustmentRequest request = pendingAdjustmentRequest(4);
        when(adjustmentRepository.findById(request.getAdjustmentRequestId())).thenReturn(Optional.of(request));
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(15);

        InventoryLedgerEntry result = service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "approver-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.ADJUSTMENT_IN);
        assertThat(result.getChangeInQuantity()).isEqualTo(4);
        verify(ledgerRepository).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void approveAdjustmentRequest_negativeQuantity_savesAdjustmentOutLedgerEntry() {
        stubLedgerSaveReturnsEntry();
        stubAdjustmentSaveReturnsRequest();
        InventoryAdjustmentRequest request = pendingAdjustmentRequest(-3);
        when(adjustmentRepository.findById(request.getAdjustmentRequestId())).thenReturn(Optional.of(request));
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(10);

        InventoryLedgerEntry result = service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "approver-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.ADJUSTMENT_OUT);
        assertThat(result.getChangeInQuantity()).isEqualTo(-3);
        verify(ledgerRepository).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void approveAdjustmentRequest_whenRequestNotFound_throwsIllegalArgumentException() {
        UUID requestId = UUID.randomUUID();
        when(adjustmentRepository.findById(requestId)).thenReturn(Optional.empty());

        Throwable exception = catchThrowable(() -> service.approveAdjustmentRequest(requestId, "approver-1"));

        assertThat(exception).isInstanceOf(IllegalArgumentException.class);
        verify(ledgerRepository, never()).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void approveAdjustmentRequest_whenNotPending_throwsIllegalStateException() {
        InventoryAdjustmentRequest request = pendingAdjustmentRequest(2);
        request.setStatus(AdjustmentRequestStatus.APPROVED);
        when(adjustmentRepository.findById(request.getAdjustmentRequestId())).thenReturn(Optional.of(request));

        Throwable exception = catchThrowable(
                () -> service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "approver-1"));

        assertThat(exception).isInstanceOf(IllegalStateException.class);
        verify(ledgerRepository, never()).save(any(InventoryLedgerEntry.class));
    }

    @Test
    void approveAdjustmentRequest_setsApprovedStatusAndApproverUserId() {
        stubLedgerSaveReturnsEntry();
        stubAdjustmentSaveReturnsRequest();
        InventoryAdjustmentRequest request = pendingAdjustmentRequest(5);
        when(adjustmentRepository.findById(request.getAdjustmentRequestId())).thenReturn(Optional.of(request));
        when(ledgerRepository.calculateOnHandQuantity(request.getProductSku())).thenReturn(20);

        service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "approver-xyz");

        ArgumentCaptor<InventoryAdjustmentRequest> adjustmentCaptor = ArgumentCaptor
                .forClass(InventoryAdjustmentRequest.class);
        verify(adjustmentRepository).save(adjustmentCaptor.capture());

        InventoryAdjustmentRequest savedRequest = adjustmentCaptor.getValue();
        assertThat(savedRequest.getStatus()).isEqualTo(AdjustmentRequestStatus.APPROVED);
        assertThat(savedRequest.getApprovedByUserId()).isEqualTo("approver-xyz");
    }

    private RecordMovementRequest baseMovementRequest(MovementType movementType, int quantity) {
        return RecordMovementRequest.builder()
                .productSku("SKU-123")
                .locationId("LOC-1")
                .toLocationId("LOC-9")
                .movementType(movementType)
                .quantity(quantity)
                .unitOfMeasure("EACH")
                .sourceTransactionId("TX-1")
                .build();
    }

    private CreateAdjustmentRequestDto baseAdjustmentRequest(int quantity) {
        return CreateAdjustmentRequestDto.builder()
                .productSku("SKU-123")
                .locationId("LOC-1")
                .quantity(quantity)
                .reasonCode("CYCLE_COUNT")
                .unitOfMeasure("EACH")
                .build();
    }

    private InventoryAdjustmentRequest pendingAdjustmentRequest(int quantity) {
        return InventoryAdjustmentRequest.builder()
                .adjustmentRequestId(UUID.randomUUID())
                .productSku("SKU-123")
                .locationId("LOC-1")
                .quantity(quantity)
                .reasonCode("CYCLE_COUNT")
                .unitOfMeasure("EACH")
                .status(AdjustmentRequestStatus.PENDING)
                .requestedByUserId("requestor-1")
                .build();
    }

    private void stubLedgerSaveReturnsEntry() {
        when(ledgerRepository.save(any(InventoryLedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubAdjustmentSaveReturnsRequest() {
        when(adjustmentRepository.save(any(InventoryAdjustmentRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
