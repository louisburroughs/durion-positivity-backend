package com.positivity.inventory.internal.service;

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
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderConsumptionException;
import com.positivity.inventory.internal.repository.AllocationRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.service.ConsumptionService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes picked items to a workorder, posting {@code WORKORDER_CONSUMPTION}
 * ledger entries and closing the originating allocations with
 * {@code ALLOCATION_RELEASED} in the same transaction (CAP-218 #662).
 *
 * <p>Allocations are resolved deterministically: pick task →
 * {@code workorderLineId} → reservation → located HARD allocations. Consumed
 * quantity is applied to allocations oldest-first; each portion releases at
 * most the allocation's un-released remainder (derived from the ledger via
 * {@code sourceTransactionId}), preserving the per-allocation
 * CREATED − RELEASED ∈ {0, allocatedQuantity} invariant. Consumption of
 * unallocated stock writes no allocation events.
 */
@Service
@Transactional
public class ConsumptionServiceImpl implements ConsumptionService {
    private final Clock clock;

    private final PickTaskRepository pickTaskRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final ReservationRepository reservationRepository;
    private final AllocationRepository allocationRepository;

    public ConsumptionServiceImpl(
            PickTaskRepository pickTaskRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            ReservationRepository reservationRepository,
            AllocationRepository allocationRepository,
            Clock clock) {
        this.clock = clock;
        this.pickTaskRepository = pickTaskRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.reservationRepository = reservationRepository;
        this.allocationRepository = allocationRepository;
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
        Map<UUID, Integer> releasedInBatch = new HashMap<>();
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

            entriesToSave.add(buildConsumptionEntry(request, item));
            entriesToSave.addAll(closeAllocations(request, item, task, releasedInBatch));
        }

        List<InventoryLedgerEntry> savedEntries = inventoryLedgerEntryRepository.saveAll(entriesToSave);
        List<UUID> ledgerEntryIds = savedEntries.stream()
                .map(InventoryLedgerEntry::getLedgerEntryId)
                .filter(Objects::nonNull)
                .toList();

        int totalItemsConsumed =
                items.stream().mapToInt(ConsumeItemLine::getQuantity).sum();

        return new ConsumptionResponse(
                UUIDv7Generator.generate(),
                request.getWorkorderId(),
                request.getPickListId(),
                totalItemsConsumed,
                Instant.now(clock),
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
            Map<UUID, Integer> releasedInBatch) {
        if (task.getWorkorderLineId() == null) {
            return List.of();
        }
        Optional<ReservationEntity> reservation =
                reservationRepository.findByWorkorderLineId(task.getWorkorderLineId());
        if (reservation.isEmpty()) {
            return List.of();
        }

        List<AllocationEntity> locatedHard = allocationRepository.findByReservation(reservation.get()).stream()
                .filter(allocation -> allocation.getAllocationState() == AllocationState.HARD)
                .filter(allocation -> allocation.getLocationId() != null)
                .filter(allocation -> allocation.getStatus() != AllocationStatus.RELEASED)
                .sorted(Comparator.comparing(
                                AllocationEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(
                                AllocationEntity::getAllocationId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (locatedHard.isEmpty()) {
            return List.of();
        }

        UUID stockItemId = reservation.get().getStockItemId();
        int netOnHand = Optional.ofNullable(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId))
                .orElse(0);

        List<InventoryLedgerEntry> releases = new ArrayList<>();
        int remainingToClose = item.getQuantity();
        int totalReleasedForReservation = 0;
        for (AllocationEntity allocation : locatedHard) {
            if (remainingToClose <= 0) {
                break;
            }
            int alreadyReleased = inventoryLedgerEntryRepository.sumChangeBySourceTransactionIdAndEventType(
                            allocation.getAllocationId().toString(), InventoryLedgerEventType.ALLOCATION_RELEASED)
                    + releasedInBatch.getOrDefault(allocation.getAllocationId(), 0);
            int allocationRemaining = allocation.getAllocatedQuantity() - alreadyReleased;
            if (allocationRemaining <= 0) {
                continue;
            }

            int release = Math.min(remainingToClose, allocationRemaining);
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

            releasedInBatch.merge(allocation.getAllocationId(), release, Integer::sum);
            if (release == allocationRemaining) {
                allocation.setStatus(AllocationStatus.RELEASED);
                allocationRepository.save(allocation);
            }
            remainingToClose -= release;
            totalReleasedForReservation += release;
        }
        if (totalReleasedForReservation > 0) {
            // PR #661 re-review finding 2: AllocationReallocationServiceImpl pools
            // reservation.allocatedQuantity as reallocatable stock; consumed stock
            // is physically gone and must leave that pool.
            ReservationEntity owning = reservation.get();
            owning.setAllocatedQuantity(Math.max(0, owning.getAllocatedQuantity() - totalReleasedForReservation));
            reservationRepository.save(owning);
        }
        return releases;
    }

    private InventoryLedgerEntry buildConsumptionEntry(ConsumeItemsRequest request, ConsumeItemLine item) {
        return InventoryLedgerEntry.builder()
                .stockItemId(item.getSkuId() == null ? "" : item.getSkuId().toString())
                .eventType(InventoryLedgerEventType.WORKORDER_CONSUMPTION)
                .changeInQuantity(-Math.abs(item.getQuantity()))
                .quantityAfter(0)
                .transactionUserId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .notes("Consumed from pick task " + item.getPickTaskId() + " for workorder " + request.getWorkorderId())
                .build();
    }
}
