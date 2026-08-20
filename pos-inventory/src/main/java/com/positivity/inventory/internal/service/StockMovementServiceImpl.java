package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.dto.InventoryLedgerEntryResponse;
import com.positivity.inventory.internal.dto.RecordMovementRequest;
import com.positivity.inventory.internal.entity.InventoryAdjustmentRequest;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentRequestStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.MovementType;
import com.positivity.inventory.internal.exception.CrossSiteTransferRequiresOrderException;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryAdjustmentRequestRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.service.StockMovementService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final LedgerPostingService ledgerPostingService;
    private final LocationRefRepository locationRefRepository;
    private final ExtStorageLocationReplicaRepository storageLocationRepository;
    private final Clock clock;
    private final QuantityScaleGuard quantityScaleGuard;

    public StockMovementServiceImpl(
            InventoryLedgerEntryRepository ledgerRepository,
            InventoryAdjustmentRequestRepository adjustmentRepository,
            LedgerPostingService ledgerPostingService,
            LocationRefRepository locationRefRepository,
            ExtStorageLocationReplicaRepository storageLocationRepository,
            QuantityScaleGuard quantityScaleGuard,
            Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.locationRefRepository = locationRefRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.quantityScaleGuard = quantityScaleGuard;
        this.clock = clock;
    }

    @Override
    @Transactional
    public @NonNull InventoryLedgerEntryResponse recordMovement(
            @NonNull RecordMovementRequest request, @NonNull String actorUserId) {
        MovementType movementType = request.getMovementType();

        // ADR-0055 (#1414): a movement posts straight into the ledger, so it is gated by the
        // product's declared divisibility exactly as receiving, ASN and returns are.
        BigDecimal movementQuantity = quantityScaleGuard.requirePostable(
                QuantityScaleGuard.productIdOf(request.getProductSku()),
                request.getProductSku(),
                "quantity",
                request.getQuantity());

        if (movementType == MovementType.PICK || movementType == MovementType.ISSUE) {
            BigDecimal onHand = Quantities.nz(ledgerRepository.calculateOnHandQuantityAtLocation(
                    request.getProductSku(), request.getFromLocationId()));
            if (Quantities.lt(onHand, request.getQuantity())) {
                throw new InsufficientStockException(request.getProductSku(), request.getFromLocationId());
            }
        }

        if (movementType == MovementType.TRANSFER) {
            if (request.getToLocationId() == null) {
                throw new IllegalArgumentException("toLocationId is required for TRANSFER movements");
            }
            rejectCrossSiteTransfer(request.getFromLocationId(), request.getToLocationId());

            InventoryLedgerEntry transferInEntry = InventoryLedgerEntry.builder()
                    .stockItemId(request.getProductSku())
                    .locationId(request.getToLocationId())
                    .fromLocationId(request.getFromLocationId())
                    .toLocationId(request.getToLocationId())
                    .eventType(InventoryLedgerEventType.TRANSFER_IN)
                    .changeInQuantity(movementQuantity)
                    .quantityAfter(calculateQuantityAfter(
                            request.getProductSku(), request.getToLocationId(), movementQuantity))
                    .unitOfMeasure(request.getUnitOfMeasure())
                    .sourceTransactionId(request.getSourceTransactionId())
                    .transactionUserId(actorUserId)
                    .timestamp(Instant.now(clock))
                    .build();
            ledgerPostingService.post(transferInEntry);
        }

        InventoryLedgerEventType eventType = mapMovementTypeToEventType(movementType);
        BigDecimal quantityDelta = isOutbound(movementType) ? movementQuantity.negate() : movementQuantity;

        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId(request.getProductSku())
                .locationId(request.getFromLocationId())
                .fromLocationId(
                        movementType == MovementType.TRANSFER
                                ? request.getFromLocationId()
                                : isOutbound(movementType) ? request.getFromLocationId() : null)
                .toLocationId(
                        movementType == MovementType.TRANSFER
                                ? request.getToLocationId()
                                : isOutbound(movementType) ? null : request.getFromLocationId())
                .eventType(eventType)
                .changeInQuantity(quantityDelta)
                .quantityAfter(
                        calculateQuantityAfter(request.getProductSku(), request.getFromLocationId(), quantityDelta))
                .unitOfMeasure(request.getUnitOfMeasure())
                .sourceTransactionId(request.getSourceTransactionId())
                .transactionUserId(actorUserId)
                .timestamp(Instant.now(clock))
                .build();

        log.debug(
                "Recorded stock movement type={} sku={} actor={}", movementType, request.getProductSku(), actorUserId);
        InventoryLedgerEntry savedEntry = ledgerPostingService.post(entry);
        return toResponse(savedEntry);
    }

    @Override
    @Transactional
    public @NonNull AdjustmentRequestResponse createAdjustmentRequest(
            @NonNull CreateAdjustmentRequestDto request, @NonNull String actorUserId) {
        InventoryAdjustmentRequest adjustmentRequest = InventoryAdjustmentRequest.builder()
                .productSku(request.getProductSku())
                .locationId(request.getLocationId())
                .quantity(request.getQuantity())
                .reasonCode(request.getReasonCode())
                .unitOfMeasure(request.getUnitOfMeasure())
                .status(AdjustmentRequestStatus.PENDING)
                .requestedByUserId(actorUserId)
                .requestedAt(Instant.now(clock))
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
    public @NonNull InventoryLedgerEntry approveAdjustmentRequest(
            @NonNull UUID adjustmentRequestId, @NonNull String approverUserId) {
        InventoryAdjustmentRequest adjustmentRequest = adjustmentRepository
                .findById(adjustmentRequestId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Adjustment request not found: " + adjustmentRequestId));

        if (adjustmentRequest.getStatus() != AdjustmentRequestStatus.PENDING) {
            throw new IllegalStateException("Adjustment request is not pending approval: " + adjustmentRequestId);
        }

        BigDecimal quantityDelta = Quantities.nz(adjustmentRequest.getQuantity());
        InventoryLedgerEventType eventType = quantityDelta.signum() >= 0
                ? InventoryLedgerEventType.ADJUSTMENT_IN
                : InventoryLedgerEventType.ADJUSTMENT_OUT;

        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId(adjustmentRequest.getProductSku())
                .locationId(adjustmentRequest.getLocationId())
                .fromLocationId(adjustmentRequest.getLocationId())
                .adjustmentId(adjustmentRequest.getAdjustmentRequestId())
                .eventType(eventType)
                .changeInQuantity(quantityDelta)
                .quantityAfter(calculateQuantityAfter(
                        adjustmentRequest.getProductSku(), adjustmentRequest.getLocationId(), quantityDelta))
                .reasonCode(adjustmentRequest.getReasonCode())
                .unitOfMeasure(adjustmentRequest.getUnitOfMeasure())
                .transactionUserId(approverUserId)
                .timestamp(Instant.now(clock))
                .build();

        adjustmentRequest.setStatus(AdjustmentRequestStatus.APPROVED);
        adjustmentRequest.setApprovedByUserId(approverUserId);
        adjustmentRequest.setApprovedAt(Instant.now(clock));
        adjustmentRepository.save(adjustmentRequest);

        return ledgerPostingService.post(entry);
    }

    /**
     * Cross-site guard (odoo-parity C2/spec C7, issue #1036): the immediate paired
     * TRANSFER_OUT/TRANSFER_IN path is retained for intra-site bin moves only. When both ends
     * resolve to sites and the sites differ, the movement is rejected with the deterministic
     * 422 {@code CROSS_SITE_TRANSFER_REQUIRES_ORDER} — cross-site moves must go through a
     * transfer order so in-transit stock is represented.
     *
     * <p>Resolution: an id present in the {@code LocationRef} roster IS a site; otherwise the
     * {@code ext_storage_location} replica maps a bin to its {@code siteId}. Ids resolvable to
     * NO site (legacy/free-form location ids outside both tables) pass through unchanged —
     * pre-C1 behavior is preserved for them because no site topology exists to judge them by.
     */
    private void rejectCrossSiteTransfer(UUID fromLocationId, UUID toLocationId) {
        UUID fromSite = resolveSite(fromLocationId);
        if (fromSite == null) {
            return;
        }
        UUID toSite = resolveSite(toLocationId);
        if (toSite == null) {
            return;
        }
        if (!fromSite.equals(toSite)) {
            throw new CrossSiteTransferRequiresOrderException(fromLocationId, fromSite, toLocationId, toSite);
        }
    }

    /** Site owning the id: the id itself when it is a roster site, else its bin's siteId, else null. */
    private @Nullable UUID resolveSite(UUID locationId) {
        if (locationId == null) {
            return null;
        }
        if (locationRefRepository.findByLocationId(locationId).isPresent()) {
            return locationId;
        }
        return storageLocationRepository
                .findById(locationId)
                .map(bin -> bin.getSiteId())
                .orElse(null);
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

    private BigDecimal calculateQuantityAfter(String stockItemId, UUID locationId, BigDecimal quantityDelta) {
        return Quantities.nz(ledgerRepository.calculateOnHandQuantityAtLocation(stockItemId, locationId))
                .add(quantityDelta);
    }

    private @NonNull InventoryLedgerEntryResponse toResponse(@NonNull InventoryLedgerEntry entry) {
        return InventoryLedgerEntryResponse.builder()
                .ledgerEntryId(entry.getLedgerEntryId())
                .stockItemId(entry.getStockItemId())
                .adjustmentId(entry.getAdjustmentId())
                .eventType(entry.getEventType())
                .changeInQuantity(entry.getChangeInQuantity())
                .quantityAfter(entry.getQuantityAfter())
                .unitCost(entry.getUnitCost())
                .transactionUserId(entry.getTransactionUserId())
                .timestamp(entry.getTimestamp())
                .locationId(entry.getLocationId())
                .fromLocationId(entry.getFromLocationId())
                .toLocationId(entry.getToLocationId())
                .reasonCode(entry.getReasonCode())
                .sourceTransactionId(entry.getSourceTransactionId())
                .unitOfMeasure(entry.getUnitOfMeasure())
                .notes(entry.getNotes())
                .build();
    }
}
