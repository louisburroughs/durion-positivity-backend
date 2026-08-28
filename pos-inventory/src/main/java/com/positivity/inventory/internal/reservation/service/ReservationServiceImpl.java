package com.positivity.inventory.internal.reservation.service;

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
import com.positivity.inventory.internal.service.InventoryFactPublisher;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.inventory.internal.service.Quantities;
import com.positivity.inventory.internal.service.StorageLocationValidationService;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private final LedgerPostingService ledgerPostingService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final StorageLocationValidationService storageLocationValidationService;

    @Override
    public @NonNull ReservationResponse createOrUpdateReservation(@NonNull CreateReservationRequest request) {
        UUID workorderLineId = request.getWorkorderLineId();
        UUID salesOrderLineId = request.getSalesOrderLineId();
        if ((workorderLineId == null) == (salesOrderLineId == null)) {
            throw new IllegalArgumentException(
                    "exactly one of workorderLineId/salesOrderLineId must be set (CAP #1315)");
        }

        Optional<ReservationEntity> existing = workorderLineId != null
                ? reservationRepository.findByWorkorderLineId(workorderLineId)
                : reservationRepository.findBySalesOrderLineId(salesOrderLineId);
        ReservationEntity reservation = existing.map(found -> updateExistingReservation(found, request))
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
        BigDecimal netOnHand = calculateNetOnHand(stockItemId);

        BigDecimal existingHard =
                allocationRepository.findByReservationAndAllocationState(reservation, AllocationState.HARD).stream()
                        .map(AllocationEntity::getAllocatedQuantity)
                        .map(Quantities::nz)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (allocation.getAllocationState() == AllocationState.HARD) {
            existingHard = existingHard.subtract(Quantities.nz(allocation.getAllocatedQuantity()));
        }

        BigDecimal availableAtp = netOnHand.subtract(existingHard);
        if (Quantities.lt(availableAtp, allocation.getAllocatedQuantity())) {
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
        BigDecimal totalAllocated = allocations.stream()
                .map(AllocationEntity::getAllocatedQuantity)
                .map(Quantities::nz)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        reservation.setAllocatedQuantity(totalAllocated);
        reservation.setStatus(
                Quantities.gte(totalAllocated, reservation.getRequiredQuantity())
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
        cancel(reservation);
    }

    @Override
    public void cancelReservationForSalesOrderLine(@NonNull UUID salesOrderLineId) {
        ReservationEntity reservation = reservationRepository
                .findBySalesOrderLineId(salesOrderLineId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", salesOrderLineId.toString()));
        cancel(reservation);
    }

    private void cancel(ReservationEntity reservation) {
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
            BigDecimal netOnHand = calculateNetOnHand(reservation.getStockItemId());
            for (AllocationEntity allocation : ledgerReleasable) {
                // CAP-218 #662: partial consumption may already have released
                // part of this allocation; release only the remainder so the
                // per-allocation CREATED - RELEASED invariant holds.
                BigDecimal alreadyReleased =
                        Quantities.nz(inventoryLedgerEntryRepository.sumChangeBySourceTransactionIdAndEventType(
                                allocation.getAllocationId().toString(), InventoryLedgerEventType.ALLOCATION_RELEASED));
                BigDecimal remaining =
                        Quantities.nz(allocation.getAllocatedQuantity()).subtract(alreadyReleased);
                if (Quantities.isPositive(remaining)) {
                    writeAllocationLedgerEntry(
                            InventoryLedgerEventType.ALLOCATION_RELEASED,
                            allocation,
                            reservation.getStockItemId(),
                            netOnHand,
                            remaining);
                }
            }
        }

        reservation.setAllocatedQuantity(BigDecimal.ZERO);
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
            BigDecimal netOnHand,
            BigDecimal quantity) {
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
        ledgerPostingService.post(entry);
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
                .salesOrderLineId(request.getSalesOrderLineId())
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

    private BigDecimal calculateNetOnHand(UUID stockItemId) {
        return Quantities.nz(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId));
    }

    private ReservationResponse toResponse(ReservationEntity reservation) {
        return ReservationResponse.builder()
                .reservationId(reservation.getReservationId())
                .workorderLineId(reservation.getWorkorderLineId())
                .salesOrderLineId(reservation.getSalesOrderLineId())
                .stockItemId(reservation.getStockItemId())
                .requiredQuantity(reservation.getRequiredQuantity())
                .allocatedQuantity(reservation.getAllocatedQuantity())
                .status(reservation.getStatus().name())
                .build();
    }
}
