package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.service.StorageLocationValidationService;
import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.entity.AllocationEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationState;
import com.positivity.inventory.internal.enums.AllocationStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.exception.InsufficientAtpException;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.AllocationRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.service.ReservationService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationServiceImpl implements ReservationService {

    private final Clock clock;
    private final ReservationRepository reservationRepository;
    private final AllocationRepository allocationRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final StorageLocationValidationService storageLocationValidationService;

    @Override
    public @NonNull ReservationResponse createOrUpdateReservation(@NonNull CreateReservationRequest request) {
        ReservationEntity reservation = reservationRepository
                .findByWorkorderLineId(request.getWorkorderLineId())
                .map(existing -> updateExistingReservation(existing, request))
                .orElseGet(() -> createReservationWithSoftAllocation(request));

        return toResponse(reservation);
    }

    @Override
    public @NonNull ReservationResponse promoteToHard(
            @NonNull UUID allocationId, @NonNull PromoteAllocationRequest request) {
        AllocationEntity allocation = allocationRepository
                .findById(allocationId)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation", allocationId.toString()));

        UUID storageLocationId = requireValidStorageLocation(request);

        ReservationEntity reservation = allocation.getReservation();
        UUID stockItemId = reservation.getStockItemId();
        int netOnHand = calculateNetOnHand(stockItemId);

        int existingHard =
                allocationRepository.findByReservationAndAllocationState(reservation, AllocationState.HARD).stream()
                        .mapToInt(AllocationEntity::getAllocatedQuantity)
                        .sum();

        if (allocation.getAllocationState() == AllocationState.HARD) {
            existingHard -= allocation.getAllocatedQuantity();
        }

        int availableAtp = netOnHand - existingHard;
        if (availableAtp < allocation.getAllocatedQuantity()) {
            reservation.setStatus(ReservationStatus.BACKORDERED);
            reservationRepository.save(reservation);
            throw new InsufficientAtpException(allocationId, allocation.getAllocatedQuantity(), availableAtp);
        }

        // A repeat promote of an already-HARD allocation with a recorded location
        // must not write a duplicate ALLOCATION_CREATED ledger event, and must keep
        // the location the existing CREATED event was recorded against (CAP-218 #656).
        boolean ledgerEventRequired =
                allocation.getAllocationState() != AllocationState.HARD || allocation.getLocationId() == null;

        // PR #661 review finding 6: surface a conflict instead of silently
        // discarding a relocation request on an already-located HARD allocation.
        if (!ledgerEventRequired && !allocation.getLocationId().equals(storageLocationId)) {
            throw new IllegalStateException("Allocation " + allocationId + " is already hardened at location "
                    + allocation.getLocationId() + "; relocation via promote is not supported");
        }

        allocation.setAllocationState(AllocationState.HARD);
        if (ledgerEventRequired) {
            allocation.setLocationId(storageLocationId);
        }
        allocation.setHardenedAt(Instant.now(clock));
        allocation.setHardenedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        allocation.setHardenedReason(request.getHardenedReason());
        allocationRepository.save(allocation);

        if (ledgerEventRequired) {
            writeAllocationLedgerEntry(
                    InventoryLedgerEventType.ALLOCATION_CREATED,
                    allocation,
                    stockItemId,
                    netOnHand,
                    allocation.getAllocatedQuantity());
        }

        List<AllocationEntity> allocations = allocationRepository.findByReservation(reservation);
        int totalAllocated = allocations.stream()
                .mapToInt(AllocationEntity::getAllocatedQuantity)
                .sum();
        reservation.setAllocatedQuantity(totalAllocated);
        reservation.setStatus(
                totalAllocated >= reservation.getRequiredQuantity()
                        ? ReservationStatus.FULFILLED
                        : ReservationStatus.PARTIALLY_FULFILLED);
        reservationRepository.save(reservation);

        return toResponse(reservation);
    }

    @Override
    public void cancelReservation(@NonNull UUID workorderLineId) {
        ReservationEntity reservation = reservationRepository
                .findByWorkorderLineId(workorderLineId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", workorderLineId.toString()));

        List<AllocationEntity> allocations = allocationRepository.findByReservation(reservation);

        // Only located HARD allocations had a matching ALLOCATION_CREATED ledger
        // event; releasing anything else would break the CREATED − RELEASED
        // invariant (CAP-218 #656).
        List<AllocationEntity> ledgerReleasable = allocations.stream()
                .filter(allocation -> allocation.getStatus() != AllocationStatus.RELEASED)
                .filter(allocation -> allocation.getAllocationState() == AllocationState.HARD)
                .filter(allocation -> allocation.getLocationId() != null)
                .toList();

        allocations.forEach(allocation -> allocation.setStatus(AllocationStatus.RELEASED));
        allocationRepository.saveAll(allocations);

        if (!ledgerReleasable.isEmpty()) {
            int netOnHand = calculateNetOnHand(reservation.getStockItemId());
            for (AllocationEntity allocation : ledgerReleasable) {
                // CAP-218 #662: partial consumption may already have released
                // part of this allocation; release only the remainder so the
                // per-allocation CREATED - RELEASED invariant holds.
                int alreadyReleased = inventoryLedgerEntryRepository.sumChangeBySourceTransactionIdAndEventType(
                        allocation.getAllocationId().toString(), InventoryLedgerEventType.ALLOCATION_RELEASED);
                int remaining = allocation.getAllocatedQuantity() - alreadyReleased;
                if (remaining > 0) {
                    writeAllocationLedgerEntry(
                            InventoryLedgerEventType.ALLOCATION_RELEASED,
                            allocation,
                            reservation.getStockItemId(),
                            netOnHand,
                            remaining);
                }
            }
        }

        reservation.setAllocatedQuantity(0);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);
    }

    private UUID requireValidStorageLocation(PromoteAllocationRequest request) {
        UUID storageLocationId = request.getStorageLocationId();
        if (storageLocationId == null) {
            throw new IllegalArgumentException("storageLocationId is required to promote an allocation to HARD");
        }

        StorageLocationValidationService.StorageLocationValidation validation =
                storageLocationValidationService.getStorageLocationValidation(storageLocationId.toString());
        if (!validation.isExists()) {
            throw new LocationNotFoundException(storageLocationId);
        }
        if (!validation.isActive()) {
            throw new IllegalArgumentException("Storage location is not active: " + storageLocationId);
        }
        return storageLocationId;
    }

    private void writeAllocationLedgerEntry(
            InventoryLedgerEventType eventType,
            AllocationEntity allocation,
            UUID stockItemId,
            int netOnHand,
            int quantity) {
        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId(stockItemId.toString())
                .eventType(eventType)
                .changeInQuantity(quantity)
                .quantityAfter(netOnHand)
                .transactionUserId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .locationId(allocation.getLocationId())
                .sourceTransactionId(
                        allocation.getAllocationId() == null
                                ? null
                                : allocation.getAllocationId().toString())
                .timestamp(Instant.now(clock))
                .build();
        inventoryLedgerEntryRepository.save(entry);
        inventoryFactPublisher.markEntry(entry);
    }

    private ReservationEntity updateExistingReservation(ReservationEntity existing, CreateReservationRequest request) {
        // PR #661 review finding 4: a located HARD allocation has an
        // ALLOCATION_CREATED ledger event recorded under the current SKU;
        // changing the SKU afterwards would skew per-SKU CREATED-RELEASED math.
        if (!existing.getStockItemId().equals(request.getStockItemId())) {
            boolean hasLocatedHard = allocationRepository.findByReservation(existing).stream()
                    .anyMatch(allocation -> allocation.getAllocationState() == AllocationState.HARD
                            && allocation.getLocationId() != null
                            && allocation.getStatus() != AllocationStatus.RELEASED);
            if (hasLocatedHard) {
                throw new IllegalStateException(
                        "Cannot change stock item on a reservation with located HARD allocations; cancel first");
            }
        }
        existing.setStockItemId(request.getStockItemId());
        existing.setRequiredQuantity(request.getRequiredQuantity());
        return reservationRepository.save(existing);
    }

    private ReservationEntity createReservationWithSoftAllocation(CreateReservationRequest request) {
        ReservationEntity reservation = ReservationEntity.builder()
                .workorderLineId(request.getWorkorderLineId())
                .stockItemId(request.getStockItemId())
                .requiredQuantity(request.getRequiredQuantity())
                .allocatedQuantity(request.getRequiredQuantity())
                .status(ReservationStatus.PENDING)
                .build();
        reservation = reservationRepository.save(reservation);

        AllocationEntity allocation = AllocationEntity.builder()
                .reservation(reservation)
                .locationId(null)
                .allocatedQuantity(request.getRequiredQuantity())
                .allocationState(AllocationState.SOFT)
                .status(AllocationStatus.ALLOCATED)
                .build();
        allocationRepository.save(allocation);

        reservation.setAllocations(List.of(allocation));
        return reservationRepository.save(reservation);
    }

    private int calculateNetOnHand(UUID stockItemId) {
        Integer onHand = inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId);
        return onHand == null ? 0 : onHand;
    }

    private ReservationResponse toResponse(ReservationEntity reservation) {
        return ReservationResponse.builder()
                .reservationId(reservation.getReservationId())
                .workorderLineId(reservation.getWorkorderLineId())
                .stockItemId(reservation.getStockItemId())
                .requiredQuantity(reservation.getRequiredQuantity())
                .allocatedQuantity(reservation.getAllocatedQuantity())
                .status(reservation.getStatus().name())
                .build();
    }
}
