package com.positivity.inventory.internal.service;

import com.positivity.domainevents.inventory.ConsumptionRecordedV1;
import com.positivity.inventory.internal.config.ConsumptionService;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemLine;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import com.positivity.inventory.internal.entity.AllocationEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationState;
import com.positivity.inventory.internal.enums.AllocationStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.exception.LotNumberRequiredException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderConsumptionException;
import com.positivity.inventory.internal.repository.AllocationRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingDecision;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes picked items to a workorder, posting {@code WORKORDER_CONSUMPTION}
 * ledger entries and closing the originating allocations with
 * {@code ALLOCATION_RELEASED} in the same transaction (CAP-218 #662).
 *
 * <p>Allocations are resolved deterministically: pick task →
 * {@code workorderLineId} → reservation → located HARD allocations. The order
 * in which consumed quantity is applied to allocations follows the sourcing
 * strategy engine (odoo-parity H1, issue #1037): under the platform-default
 * FIFO strategy allocations close oldest-first (creation timestamp, then
 * allocation id) — exactly the pre-H1 behavior — while PROXIMITY and
 * HIGHEST_STOCK close allocations grouped by the engine's location ordering
 * first (allocations at better-ranked locations close before older ones at
 * worse-ranked locations), still oldest-first within a location. Each portion
 * releases at most the allocation's un-released remainder (derived from the
 * ledger via {@code sourceTransactionId}), preserving the per-allocation
 * CREATED − RELEASED ∈ {0, allocatedQuantity} invariant. Consumption of
 * unallocated stock writes no allocation events.
 */
@Service
@Transactional
public class ConsumptionServiceImpl implements ConsumptionService {

    /** costSource label when the J1 engine costed at least one consumed line (odoo-parity J3). */
    static final String ENGINE_COST_SOURCE = "ENGINE";

    /** costSource label when no consumed line could be engine-costed (odoo-parity J3). */
    static final String NONE_COST_SOURCE = "NONE";

    private final Clock clock;

    private final PickTaskRepository pickTaskRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final ReservationRepository reservationRepository;
    private final AllocationRepository allocationRepository;
    private final LedgerPostingService ledgerPostingService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final @Nullable SourcingStrategyService sourcingStrategyService;
    private final @Nullable InventoryLotOutboundService lotOutboundService;

    @Autowired
    public ConsumptionServiceImpl(
            PickTaskRepository pickTaskRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            ReservationRepository reservationRepository,
            AllocationRepository allocationRepository,
            LedgerPostingService ledgerPostingService,
            InventoryFactPublisher inventoryFactPublisher,
            Clock clock,
            SourcingStrategyService sourcingStrategyService,
            InventoryLotOutboundService lotOutboundService) {
        this.clock = clock;
        this.inventoryFactPublisher = inventoryFactPublisher;
        this.pickTaskRepository = pickTaskRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.reservationRepository = reservationRepository;
        this.allocationRepository = allocationRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.sourcingStrategyService = sourcingStrategyService;
        this.lotOutboundService = lotOutboundService;
    }

    /**
     * Engine-less constructor kept for the pre-H1 unit-test fixtures: without a
     * {@link SourcingStrategyService} the allocation-close order is the platform-default FIFO
     * (oldest-first) — identical to the engine's default-config decision, which the H1
     * regression tests prove. The absent {@link InventoryLotOutboundService} likewise means
     * every SKU behaves untracked (odoo-parity E2, #1042) — a task-recorded
     * {@code pickedLotId} is still stamped, but no lot gating applies.
     */
    public ConsumptionServiceImpl(
            PickTaskRepository pickTaskRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            ReservationRepository reservationRepository,
            AllocationRepository allocationRepository,
            LedgerPostingService ledgerPostingService,
            InventoryFactPublisher inventoryFactPublisher,
            Clock clock,
            @Nullable SourcingStrategyService sourcingStrategyService) {
        this(
                pickTaskRepository,
                inventoryLedgerEntryRepository,
                reservationRepository,
                allocationRepository,
                ledgerPostingService,
                inventoryFactPublisher,
                clock,
                sourcingStrategyService,
                null);
    }

    /** See {@link #ConsumptionServiceImpl(PickTaskRepository, InventoryLedgerEntryRepository,
     * ReservationRepository, AllocationRepository, LedgerPostingService, InventoryFactPublisher,
     * Clock, SourcingStrategyService)}. */
    public ConsumptionServiceImpl(
            PickTaskRepository pickTaskRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            ReservationRepository reservationRepository,
            AllocationRepository allocationRepository,
            LedgerPostingService ledgerPostingService,
            InventoryFactPublisher inventoryFactPublisher,
            Clock clock) {
        this(
                pickTaskRepository,
                inventoryLedgerEntryRepository,
                reservationRepository,
                allocationRepository,
                ledgerPostingService,
                inventoryFactPublisher,
                clock,
                null,
                null);
    }

    @Override
    public @NonNull ConsumptionResponse consumePickedItems(@NonNull ConsumeItemsRequest request) {
        if (request.getWorkorderId() == null) {
            throw new IllegalArgumentException("workorderId must not be null");
        }

        List<ConsumeItemLine> items = request.getItems() == null ? List.of() : request.getItems();

        List<InventoryLedgerEntry> entriesToSave = new ArrayList<>();
        // Releases accumulated in this request are not yet visible to the
        // ledger-derived "already released" lookup; track them per allocation
        // so multi-item requests cannot over-release (PR #661 re-review finding 1).
        Map<UUID, BigDecimal> releasedInBatch = new HashMap<>();
        for (ConsumeItemLine item : items) {
            PickTaskEntity task = pickTaskRepository
                    .findById(item.getPickTaskId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "PickTask", item.getPickTaskId().toString()));

            if (task.getStatus() != PickTaskStatus.PICKED) {
                throw new WorkorderConsumptionException("Item not picked: " + item.getPickTaskId());
            }

            if (item.getQuantity() > task.getQuantityPicked()) {
                throw new WorkorderConsumptionException(
                        "Requested quantity exceeds picked quantity for task: " + item.getPickTaskId());
            }

            entriesToSave.add(buildConsumptionEntry(request, item, resolveConsumptionLot(item, task)));
            entriesToSave.addAll(closeAllocations(request, item, task, releasedInBatch));
        }

        List<InventoryLedgerEntry> savedEntries = ledgerPostingService.postAll(entriesToSave);
        inventoryFactPublisher.markEntries(savedEntries);
        List<UUID> ledgerEntryIds = savedEntries.stream()
                .map(InventoryLedgerEntry::getLedgerEntryId)
                .filter(Objects::nonNull)
                .toList();

        int totalItemsConsumed =
                items.stream().mapToInt(ConsumeItemLine::getQuantity).sum();

        UUID consumptionId = UUIDv7Generator.generate();
        Instant consumedAt = Instant.now(clock);
        // #901: workorder's ext_pick_task replica accumulates consumed quantities from this fact.
        // odoo-parity J3 (#1053): the fact also carries the J1 engine's method-derived cost. Each
        // item produced exactly one WORKORDER_CONSUMPTION entry (in item order), which the funnel's
        // LedgerCostingService stamped with the engine unitCost — zip them back by position.
        List<InventoryLedgerEntry> consumptionEntries = savedEntries.stream()
                .filter(entry -> entry.getEventType() == InventoryLedgerEventType.WORKORDER_CONSUMPTION)
                .toList();
        List<ConsumptionRecordedV1.Line> lines = new ArrayList<>();
        boolean anyCosted = false;
        for (int i = 0; i < items.size(); i++) {
            ConsumeItemLine item = items.get(i);
            BigDecimal unitCost =
                    i < consumptionEntries.size() ? consumptionEntries.get(i).getUnitCost() : null;
            BigDecimal extendedCost =
                    unitCost == null ? null : unitCost.multiply(BigDecimal.valueOf(Math.abs(item.getQuantity())));
            if (unitCost != null) {
                anyCosted = true;
            }
            lines.add(new ConsumptionRecordedV1.Line(
                    item.getPickTaskId(), item.getSkuId(), item.getQuantity(), unitCost, extendedCost));
        }
        String costSource = anyCosted ? ENGINE_COST_SOURCE : NONE_COST_SOURCE;

        inventoryFactPublisher.recordConsumption(new ConsumptionRecordedV1(
                consumptionId,
                request.getWorkorderId(),
                request.getPickListId(),
                totalItemsConsumed,
                consumedAt,
                lines,
                costSource));

        return new ConsumptionResponse(
                consumptionId,
                request.getWorkorderId(),
                request.getPickListId(),
                totalItemsConsumed,
                consumedAt,
                ledgerEntryIds);
    }

    /**
     * Closes located HARD allocations for the consumed quantity, oldest first.
     * Returns the {@code ALLOCATION_RELEASED} entries to persist; transitions
     * fully released allocations to {@link AllocationStatus#RELEASED}.
     */
    private List<InventoryLedgerEntry> closeAllocations(
            ConsumeItemsRequest request,
            ConsumeItemLine item,
            PickTaskEntity task,
            Map<UUID, BigDecimal> releasedInBatch) {
        if (task.getWorkorderLineId() == null) {
            return List.of();
        }
        Optional<ReservationEntity> reservation =
                reservationRepository.findByWorkorderLineId(task.getWorkorderLineId());
        if (reservation.isEmpty()) {
            return List.of();
        }

        List<AllocationEntity> unordered = allocationRepository.findByReservation(reservation.get()).stream()
                .filter(allocation -> allocation.getAllocationState() == AllocationState.HARD)
                .filter(allocation -> allocation.getLocationId() != null)
                .filter(allocation -> allocation.getStatus() != AllocationStatus.RELEASED)
                .toList();
        if (unordered.isEmpty()) {
            return List.of();
        }
        List<AllocationEntity> locatedHard =
                orderAllocationsForClose(unordered, reservation.get().getStockItemId(), task);

        UUID stockItemId = reservation.get().getStockItemId();
        BigDecimal netOnHand = Quantities.nz(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId));

        List<InventoryLedgerEntry> releases = new ArrayList<>();
        // The pick chain is still integral (pick-task quantities widen with the UOM work in
        // ADR-0055 stage 3/4), so this widens at the boundary rather than narrowing: an int
        // consumed quantity is exactly representable as a BigDecimal, and the allocation math it
        // feeds is decimal because allocations are.
        BigDecimal remainingToClose = BigDecimal.valueOf(item.getQuantity());
        BigDecimal totalReleasedForReservation = BigDecimal.ZERO;
        for (AllocationEntity allocation : locatedHard) {
            if (!Quantities.isPositive(remainingToClose)) {
                break;
            }
            BigDecimal alreadyReleased = Quantities.nz(
                            inventoryLedgerEntryRepository.sumChangeBySourceTransactionIdAndEventType(
                                    allocation.getAllocationId().toString(),
                                    InventoryLedgerEventType.ALLOCATION_RELEASED))
                    .add(releasedInBatch.getOrDefault(allocation.getAllocationId(), BigDecimal.ZERO));
            BigDecimal allocationRemaining =
                    Quantities.nz(allocation.getAllocatedQuantity()).subtract(alreadyReleased);
            if (!Quantities.isPositive(allocationRemaining)) {
                continue;
            }

            BigDecimal release = Quantities.min(remainingToClose, allocationRemaining);
            releases.add(InventoryLedgerEntry.builder()
                    .stockItemId(stockItemId.toString())
                    .eventType(InventoryLedgerEventType.ALLOCATION_RELEASED)
                    .changeInQuantity(release)
                    .quantityAfter(netOnHand)
                    .transactionUserId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .locationId(allocation.getLocationId())
                    .sourceTransactionId(allocation.getAllocationId().toString())
                    .notes("Closed by consumption of pick task " + item.getPickTaskId() + " for workorder "
                            + request.getWorkorderId())
                    .timestamp(Instant.now(clock))
                    .build());

            releasedInBatch.merge(allocation.getAllocationId(), release, BigDecimal::add);
            if (release == allocationRemaining) {
                allocation.setStatus(AllocationStatus.RELEASED);
                allocationRepository.save(allocation);
            }
            remainingToClose = remainingToClose.subtract(release);
            totalReleasedForReservation = totalReleasedForReservation.add(release);
        }
        if (Quantities.isPositive(totalReleasedForReservation)) {
            // PR #661 re-review finding 2: AllocationReallocationServiceImpl pools
            // reservation.allocatedQuantity as reallocatable stock; consumed stock
            // is physically gone and must leave that pool.
            ReservationEntity owning = reservation.get();
            owning.setAllocatedQuantity(Quantities.atLeastZero(
                    Quantities.nz(owning.getAllocatedQuantity()).subtract(totalReleasedForReservation)));
            reservationRepository.save(owning);
        }
        return releases;
    }

    /**
     * Allocation-close order per the sourcing strategy engine (odoo-parity
     * H1, issue #1037).
     *
     * <p>FIFO (the platform default, and the FEFO/PROXIMITY fallback) closes
     * oldest allocation first — allocation creation follows receipt order, so
     * this is FIFO at allocation granularity and is bit-identical to the
     * pre-H1 hardcoded ordering. Any other effective strategy ranks the
     * allocations' distinct locations through the engine (PROXIMITY anchors
     * on the pick task's scanned/suggested location) and closes allocations
     * by location rank first, oldest-first within a location, allocation id
     * as the final tie-breaker.
     */
    private List<AllocationEntity> orderAllocationsForClose(
            List<AllocationEntity> allocations, UUID stockItemId, PickTaskEntity task) {
        Comparator<AllocationEntity> oldestFirst = Comparator.comparing(
                        AllocationEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AllocationEntity::getAllocationId, Comparator.nullsLast(Comparator.naturalOrder()));
        if (sourcingStrategyService == null) {
            return allocations.stream().sorted(oldestFirst).toList();
        }

        List<SourcingCandidate> candidates = allocations.stream()
                .map(AllocationEntity::getLocationId)
                .distinct()
                .map(locationId -> new SourcingCandidate(locationId, null))
                .toList();
        SourcingDecision decision = sourcingStrategyService.orderCandidates(
                new SourcingSelection(stockItemId.toString(), null, task.getSuggestedLocationId(), candidates));
        if (decision.effectiveStrategy() == SourcingStrategy.FIFO) {
            return allocations.stream().sorted(oldestFirst).toList();
        }

        Map<UUID, Integer> locationRank = new HashMap<>();
        List<SourcingCandidate> ordered = decision.orderedCandidates();
        for (int i = 0; i < ordered.size(); i++) {
            locationRank.put(ordered.get(i).locationId(), i);
        }
        return allocations.stream()
                .sorted(Comparator.<AllocationEntity>comparingInt(
                                allocation -> locationRank.getOrDefault(allocation.getLocationId(), Integer.MAX_VALUE))
                        .thenComparing(oldestFirst))
                .toList();
    }

    /**
     * Lot the {@code WORKORDER_CONSUMPTION} entry draws from (odoo-parity E2, issue #1042).
     * Source order for LOT-tracked SKUs: an explicit {@code lotNumber} on the request line
     * (validated: must exist and be ACTIVE) overrides; otherwise the lot recorded at pick
     * confirmation ({@code PickTaskEntity.pickedLotId} — the pick→consumption linkage);
     * neither present is a 422 {@code LOT_NUMBER_REQUIRED}. Untracked SKUs return the task's
     * recorded lot (always null pre-E2) with no validation — byte-identical behavior.
     *
     * <p>The {@code ALLOCATION_RELEASED} entries this flow also writes stay lot-null by
     * design: allocations are location-level and never pin lots (spec §6 E2 decision).
     */
    private @Nullable UUID resolveConsumptionLot(ConsumeItemLine item, PickTaskEntity task) {
        if (lotOutboundService == null) {
            return task.getPickedLotId();
        }
        String stockItemId = item.getSkuId() == null ? "" : item.getSkuId().toString();
        if (!lotOutboundService.isLotTracked(stockItemId)) {
            return task.getPickedLotId();
        }
        if (item.getLotNumber() != null && !item.getLotNumber().isBlank()) {
            return lotOutboundService.resolveOutboundLot(stockItemId, item.getLotNumber());
        }
        if (task.getPickedLotId() != null) {
            return task.getPickedLotId();
        }
        throw new LotNumberRequiredException(stockItemId);
    }

    private InventoryLedgerEntry buildConsumptionEntry(
            ConsumeItemsRequest request, ConsumeItemLine item, @Nullable UUID lotId) {
        return InventoryLedgerEntry.builder()
                .stockItemId(item.getSkuId() == null ? "" : item.getSkuId().toString())
                .eventType(InventoryLedgerEventType.WORKORDER_CONSUMPTION)
                .changeInQuantity(BigDecimal.valueOf(item.getQuantity()).abs().negate())
                .quantityAfter(BigDecimal.ZERO)
                .lotId(lotId)
                .transactionUserId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .notes("Consumed from pick task " + item.getPickTaskId() + " for workorder " + request.getWorkorderId())
                .build();
    }
}
