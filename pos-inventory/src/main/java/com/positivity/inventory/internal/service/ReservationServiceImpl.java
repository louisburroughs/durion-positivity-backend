package com.positivity.inventory.internal.service;

import java.time.Clock;

import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.entity.AllocationEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationState;
import com.positivity.inventory.internal.enums.AllocationStatus;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.exception.InsufficientAtpException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.AllocationRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.security.common.SecurityContextHelper;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import com.positivity.inventory.service.ReservationService;
import java.util.UUID;
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

    @Override
    public @NonNull ReservationResponse createOrUpdateReservation(@NonNull CreateReservationRequest request) {
        ReservationEntity reservation = reservationRepository.findByWorkorderLineId(request.getWorkorderLineId())
                .map(existing -> updateExistingReservation(existing, request))
                .orElseGet(() -> createReservationWithSoftAllocation(request));

        return toResponse(reservation);
    }

    @Override
    public @NonNull ReservationResponse promoteToHard(
            @NonNull UUID allocationId,
            @NonNull PromoteAllocationRequest request) {
        AllocationEntity allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation", allocationId.toString()));

        ReservationEntity reservation = allocation.getReservation();
        UUID stockItemId = reservation.getStockItemId();
        int netOnHand = calculateNetOnHand(stockItemId);

        int existingHard = allocationRepository.findByReservationAndAllocationState(reservation, AllocationState.HARD)
                .stream()
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

        allocation.setAllocationState(AllocationState.HARD);
        allocation.setHardenedAt(Instant.now(clock));
        allocation.setHardenedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        allocation.setHardenedReason(request.getHardenedReason());
        allocationRepository.save(allocation);

        List<AllocationEntity> allocations = allocationRepository.findByReservation(reservation);
        int totalAllocated = allocations.stream().mapToInt(AllocationEntity::getAllocatedQuantity).sum();
        reservation.setAllocatedQuantity(totalAllocated);
        reservation.setStatus(totalAllocated >= reservation.getRequiredQuantity()
                ? ReservationStatus.FULFILLED
                : ReservationStatus.PARTIALLY_FULFILLED);
        reservationRepository.save(reservation);

        return toResponse(reservation);
    }

    @Override
    public void cancelReservation(@NonNull UUID workorderLineId) {
        ReservationEntity reservation = reservationRepository.findByWorkorderLineId(workorderLineId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", workorderLineId.toString()));

        List<AllocationEntity> allocations = allocationRepository.findByReservation(reservation);
        allocations.forEach(allocation -> allocation.setStatus(AllocationStatus.RELEASED));
        allocationRepository.saveAll(allocations);

        reservation.setAllocatedQuantity(0);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);
    }

    private ReservationEntity updateExistingReservation(ReservationEntity existing, CreateReservationRequest request) {
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
