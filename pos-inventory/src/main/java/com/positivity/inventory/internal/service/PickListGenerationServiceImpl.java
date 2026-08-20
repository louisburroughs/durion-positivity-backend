package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.picklist.GeneratePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.PickListRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingDecision;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import com.positivity.inventory.service.PickListGenerationService;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates a pick list with one task per line item, suggesting each task's
 * pick location through the sourcing strategy engine (odoo-parity H1/H2,
 * issue #1037).
 *
 * <p>Candidate locations are the SKU's {@code inventory_stock_summary} rows
 * with positive available stock ({@code onHand - allocated}); the engine
 * orders them per the resolved strategy and the first candidate becomes
 * {@code suggestedLocationId}. The decision's effective strategy is recorded
 * as the task's {@code sourcingReason} (spec H2 — "why this bin"). SKUs with
 * no available stock get no suggestion and no reason.
 *
 * <p>The task's {@code productId} is the line's SKU parsed as a UUID (ledger
 * stock-item ids are product UUIDs rendered as strings — see
 * {@code PickTaskRepository}); for free-text SKUs it falls back to the
 * reservation referenced by the line's {@code workorderLineId}.
 */
@Service
@Transactional
public class PickListGenerationServiceImpl implements PickListGenerationService {

    private final PickListRepository pickListRepository;
    private final PickTaskRepository pickTaskRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final SourcingStrategyService sourcingStrategyService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final @Nullable InventoryLotOutboundService lotOutboundService;

    @Autowired
    public PickListGenerationServiceImpl(
            PickListRepository pickListRepository,
            PickTaskRepository pickTaskRepository,
            ReservationRepository reservationRepository,
            InventoryStockSummaryRepository stockSummaryRepository,
            SourcingStrategyService sourcingStrategyService,
            InventoryFactPublisher inventoryFactPublisher,
            InventoryLotOutboundService lotOutboundService) {
        this.pickListRepository = pickListRepository;
        this.pickTaskRepository = pickTaskRepository;
        this.reservationRepository = reservationRepository;
        this.stockSummaryRepository = stockSummaryRepository;
        this.sourcingStrategyService = sourcingStrategyService;
        this.inventoryFactPublisher = inventoryFactPublisher;
        this.lotOutboundService = lotOutboundService;
    }

    /**
     * Lot-suggestion-less constructor kept for the pre-E2 unit-test fixtures: without an
     * {@link InventoryLotOutboundService} every SKU behaves untracked (no
     * {@code suggestedLotNumber}) — identical to the service's behavior for NONE-tracked
     * products.
     */
    public PickListGenerationServiceImpl(
            PickListRepository pickListRepository,
            PickTaskRepository pickTaskRepository,
            ReservationRepository reservationRepository,
            InventoryStockSummaryRepository stockSummaryRepository,
            SourcingStrategyService sourcingStrategyService,
            InventoryFactPublisher inventoryFactPublisher) {
        this(
                pickListRepository,
                pickTaskRepository,
                reservationRepository,
                stockSummaryRepository,
                sourcingStrategyService,
                inventoryFactPublisher,
                null);
    }

    @Override
    public @NonNull PickListResponse generatePickList(@NonNull GeneratePickListRequest request) {
        if (request.getWorkorderId() == null) {
            throw new IllegalArgumentException("workorderId is required");
        }
        List<GeneratePickListRequest.PickLineItem> lineItems =
                request.getLineItems() == null ? List.of() : request.getLineItems();
        if (lineItems.isEmpty()) {
            throw new IllegalArgumentException("lineItems must not be empty");
        }

        PickListEntity pickList = pickListRepository.save(PickListEntity.builder()
                .workorderId(request.getWorkorderId())
                .status(PickListStatus.DRAFT)
                .priority(request.getBasePriority())
                .dueAt(request.getScheduledStartAt())
                .build());

        int sortOrder = 0;
        for (GeneratePickListRequest.PickLineItem line : lineItems) {
            PickTaskEntity task = buildTask(request, pickList, line, sortOrder++);
            PickTaskEntity saved = pickTaskRepository.save(task);
            inventoryFactPublisher.markPickTaskChanged(saved.getPickTaskId());
        }
        inventoryFactPublisher.markPickListChanged(pickList.getPickListId());

        return toResponse(pickList);
    }

    @Override
    public @NonNull PickListResponse getPickList(@NonNull UUID pickListId) {
        PickListEntity pickList = pickListRepository
                .findById(pickListId)
                .orElseThrow(() -> new ResourceNotFoundException("PickList", pickListId.toString()));
        return toResponse(pickList);
    }

    private PickTaskEntity buildTask(
            GeneratePickListRequest request,
            PickListEntity pickList,
            GeneratePickListRequest.PickLineItem line,
            int sortOrder) {
        SourcingDecision decision = suggestLocation(line.getSku());
        UUID suggestedLocationId = decision.orderedCandidates().isEmpty()
                ? null
                : decision.orderedCandidates().getFirst().locationId();

        // odoo-parity E2/E3 (#1042/#1047): advisory lot suggestion for LOT-tracked SKUs at the
        // suggested location — FEFO (earliest expiry) when the lots carry expiration, else FIFO by
        // receivedAt, with expired lots guarded out on read. Confirm may override with any valid
        // ACTIVE lot.
        String suggestedLotNumber = lotOutboundService == null
                ? null
                : lotOutboundService.suggestLotNumber(line.getSku(), suggestedLocationId);

        return PickTaskEntity.builder()
                .pickList(pickList)
                .productId(resolveProductId(line))
                .sku(line.getSku())
                .quantityRequired(line.getQuantity())
                .sortOrder(sortOrder)
                .priority(request.getBasePriority())
                .dueAt(request.getScheduledStartAt())
                .workorderLineId(line.getWorkorderLineId())
                .status(PickTaskStatus.PENDING)
                .suggestedLocationId(suggestedLocationId)
                .sourcingReason(suggestedLocationId == null ? null : decision.effectiveStrategy())
                .suggestedLotNumber(suggestedLotNumber)
                .build();
    }

    /**
     * Orders the SKU's positive-available summary locations through the
     * sourcing engine. Site scope and PROXIMITY reference are unset here —
     * the engine derives the site from the candidates' storage-location
     * replicas, and PROXIMITY without a reference falls back to FIFO.
     */
    private SourcingDecision suggestLocation(String sku) {
        List<SourcingCandidate> candidates = stockSummaryRepository.findByStockItemId(sku).stream()
                .filter(summary -> summary.getLocationId() != null)
                .filter(summary -> Quantities.isPositive(
                        Quantities.nz(summary.getOnHand()).subtract(Quantities.nz(summary.getAllocated()))))
                .map(summary -> new SourcingCandidate(
                        summary.getLocationId(),
                        Quantities.nz(summary.getOnHand()).subtract(Quantities.nz(summary.getAllocated()))))
                .toList();
        return sourcingStrategyService.orderCandidates(new SourcingSelection(sku, null, null, candidates));
    }

    private UUID resolveProductId(GeneratePickListRequest.PickLineItem line) {
        UUID parsed = tryParseUuid(line.getSku());
        if (parsed != null) {
            return parsed;
        }
        if (line.getWorkorderLineId() != null) {
            UUID fromReservation = reservationRepository
                    .findByWorkorderLineId(line.getWorkorderLineId())
                    .map(ReservationEntity::getStockItemId)
                    .orElse(null);
            if (fromReservation != null) {
                return fromReservation;
            }
        }
        throw new IllegalArgumentException(
                "Cannot resolve product id for sku '" + line.getSku() + "': not a product UUID and no reservation"
                        + " found for workorderLineId " + line.getWorkorderLineId());
    }

    private @Nullable UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private PickListResponse toResponse(PickListEntity entity) {
        return new PickListResponse(
                entity.getPickListId(),
                entity.getWorkorderId(),
                entity.getStatus(),
                entity.getPriority(),
                entity.getDueAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
