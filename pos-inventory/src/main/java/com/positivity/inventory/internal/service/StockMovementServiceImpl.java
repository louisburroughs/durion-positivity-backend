package com.positivity.inventory.internal.service;

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
import com.positivity.inventory.service.StockMovementService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Implementation of StockMovementService that records inventory movements.
 *
 * Issue: CAP-215 Story #37
 */
@Service
@Slf4j
public class StockMovementServiceImpl implements StockMovementService {

    private final InventoryLedgerEntryRepository ledgerRepository;
    private final InventoryAdjustmentRequestRepository adjustmentRepository;

    public StockMovementServiceImpl(
            InventoryLedgerEntryRepository ledgerRepository,
            InventoryAdjustmentRequestRepository adjustmentRepository) {
        this.ledgerRepository = ledgerRepository;
        this.adjustmentRepository = adjustmentRepository;
    }

    @Override
    @Transactional
    public @NonNull InventoryLedgerEntry recordMovement(@NonNull RecordMovementRequest request,
            @NonNull String actorUserId) {
        MovementType movementType = request.getMovementType();

        if (movementType == MovementType.PICK || movementType == MovementType.ISSUE) {
            Integer currentOnHand = ledgerRepository.calculateOnHandQuantity(request.getProductSku());
            int onHand = currentOnHand != null ? currentOnHand : 0;
            if (onHand < request.getQuantity()) {
                throw new InsufficientStockException(request.getProductSku(), request.getLocationId());
            }
        }

        if (movementType == MovementType.TRANSFER) {
            InventoryLedgerEntry transferInEntry = InventoryLedgerEntry.builder()
                    .stockItemId(request.getProductSku())
                    .locationId(request.getToLocationId())
                    .fromLocationId(request.getLocationId())
                    .toLocationId(request.getToLocationId())
                    .eventType(InventoryLedgerEventType.TRANSFER_IN)
                    .changeInQuantity(request.getQuantity())
                    .quantityAfter(calculateQuantityAfter(request.getProductSku(), request.getQuantity()))
                    .unitOfMeasure(request.getUnitOfMeasure())
                    .sourceTransactionId(request.getSourceTransactionId())
                    .transactionUserId(actorUserId)
                    .timestamp(Instant.now())
                    .build();
            ledgerRepository.save(transferInEntry);
        }

        InventoryLedgerEventType eventType = mapMovementTypeToEventType(movementType);
        int quantityDelta = isOutbound(movementType) ? -request.getQuantity() : request.getQuantity();

        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId(request.getProductSku())
                .locationId(request.getLocationId())
                .fromLocationId(movementType == MovementType.TRANSFER ? request.getLocationId()
                        : isOutbound(movementType) ? request.getLocationId() : null)
                .toLocationId(movementType == MovementType.TRANSFER ? request.getToLocationId()
                        : isOutbound(movementType) ? null : request.getLocationId())
                .eventType(eventType)
                .changeInQuantity(quantityDelta)
                .quantityAfter(calculateQuantityAfter(request.getProductSku(), quantityDelta))
                .unitOfMeasure(request.getUnitOfMeasure())
                .sourceTransactionId(request.getSourceTransactionId())
                .transactionUserId(actorUserId)
                .timestamp(Instant.now())
                .build();

        log.debug("Recorded stock movement type={} sku={} actor={}", movementType, request.getProductSku(),
                actorUserId);
        return ledgerRepository.save(entry);
    }

    @Override
    @Transactional
    public @NonNull AdjustmentRequestResponse createAdjustmentRequest(@NonNull CreateAdjustmentRequestDto request,
            @NonNull String actorUserId) {
        InventoryAdjustmentRequest adjustmentRequest = InventoryAdjustmentRequest.builder()
                .productSku(request.getProductSku())
                .locationId(request.getLocationId())
                .quantity(request.getQuantity())
                .reasonCode(request.getReasonCode())
                .unitOfMeasure(request.getUnitOfMeasure())
                .status(AdjustmentRequestStatus.PENDING)
                .requestedByUserId(actorUserId)
                .requestedAt(Instant.now())
                .build();
        InventoryAdjustmentRequest savedRequest = adjustmentRepository.save(adjustmentRequest);
        return AdjustmentRequestResponse.builder()
                .adjustmentRequestId(savedRequest.getAdjustmentRequestId())
                .productSku(savedRequest.getProductSku())
                .locationId(savedRequest.getLocationId())
                .quantity(savedRequest.getQuantity())
                .reasonCode(savedRequest.getReasonCode())
                .status(savedRequest.getStatus().name())
                .build();
    }

    @Override
    @Transactional
    public @NonNull InventoryLedgerEntry approveAdjustmentRequest(@NonNull UUID adjustmentRequestId,
            @NonNull String approverUserId) {
        InventoryAdjustmentRequest adjustmentRequest = adjustmentRepository.findById(adjustmentRequestId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Adjustment request not found: " + adjustmentRequestId));

        if (adjustmentRequest.getStatus() != AdjustmentRequestStatus.PENDING) {
            throw new IllegalStateException("Adjustment request is not pending approval: " + adjustmentRequestId);
        }

        int quantityDelta = adjustmentRequest.getQuantity();
        InventoryLedgerEventType eventType = quantityDelta >= 0
                ? InventoryLedgerEventType.ADJUSTMENT_IN
                : InventoryLedgerEventType.ADJUSTMENT_OUT;

        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId(adjustmentRequest.getProductSku())
                .locationId(adjustmentRequest.getLocationId())
                .fromLocationId(adjustmentRequest.getLocationId())
                .adjustmentId(adjustmentRequest.getAdjustmentRequestId())
                .eventType(eventType)
                .changeInQuantity(quantityDelta)
                .quantityAfter(calculateQuantityAfter(adjustmentRequest.getProductSku(), quantityDelta))
                .reasonCode(adjustmentRequest.getReasonCode())
                .unitOfMeasure(adjustmentRequest.getUnitOfMeasure())
                .transactionUserId(approverUserId)
                .timestamp(Instant.now())
                .build();

        adjustmentRequest.setStatus(AdjustmentRequestStatus.APPROVED);
        adjustmentRequest.setApprovedByUserId(approverUserId);
        adjustmentRequest.setApprovedAt(Instant.now());
        adjustmentRepository.save(adjustmentRequest);

        return ledgerRepository.save(entry);
    }

    private InventoryLedgerEventType mapMovementTypeToEventType(MovementType movementType) {
        return switch (movementType) {
            case RECEIVE -> InventoryLedgerEventType.GOODS_RECEIPT;
            case PUT_AWAY -> InventoryLedgerEventType.PUTAWAY;
            case PICK, ISSUE -> InventoryLedgerEventType.GOODS_ISSUE;
            case RETURN -> InventoryLedgerEventType.RETURN_TO_STOCK;
            case TRANSFER -> InventoryLedgerEventType.TRANSFER_OUT;
            case ADJUST -> throw new IllegalArgumentException("ADJUST must use adjustment workflow");
        };
    }

    private boolean isOutbound(MovementType movementType) {
        return movementType == MovementType.PICK
                || movementType == MovementType.ISSUE
                || movementType == MovementType.TRANSFER;
    }

    private int calculateQuantityAfter(String stockItemId, int quantityDelta) {
        Integer currentOnHand = ledgerRepository.calculateOnHandQuantity(stockItemId);
        int onHand = currentOnHand != null ? currentOnHand : 0;
        return onHand + quantityDelta;
    }
}
