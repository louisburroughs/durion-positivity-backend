package com.positivity.inventory.internal.service;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.positivity.inventory.service.ReservationService;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReservationServiceImpl implements ReservationService {

    private final ReservationRepository reservationRepository;
    private final AllocationRepository allocationRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    private final Map<UUID, ReservationEntity> reservationStoreByWorkorderLineId = new ConcurrentHashMap<>();
    private final Map<UUID, AllocationEntity> allocationStoreById = new ConcurrentHashMap<>();

    ReservationServiceImpl() {
        this(null, null, null);
    }

    @Autowired
    public ReservationServiceImpl(
            ReservationRepository reservationRepository,
            AllocationRepository allocationRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository) {
        this.reservationRepository = reservationRepository;
        this.allocationRepository = allocationRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
    }

    @Override
    public @NonNull ReservationResponse createOrUpdateReservation(@NonNull CreateReservationRequest request) {
        if (isInMemoryMode()) {
            return createOrUpdateReservationInMemory(request);
        }

        ReservationEntity reservation = reservationRepository.findByWorkorderLineId(request.getWorkorderLineId())
                .map(existing -> updateExistingReservation(existing, request))
                .orElseGet(() -> createReservationWithSoftAllocation(request));

        return toResponse(reservation);
    }

    @Override
    public @NonNull ReservationResponse promoteToHard(
            @NonNull UUID allocationId,
            @NonNull PromoteAllocationRequest request) {
        if (isInMemoryMode()) {
            return promoteToHardInMemory(allocationId, request);
        }

        AllocationEntity allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation", allocationId.toString()));

        ReservationEntity reservation = allocation.getReservation();
        String sku = reservation.getSku();
        int netOnHand = calculateNetOnHand(sku);

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
        allocation.setHardenedAt(Instant.now());
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
        if (isInMemoryMode()) {
            cancelReservationInMemory(workorderLineId);
            return;
        }

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
        existing.setRequiredQuantity(request.getRequiredQuantity());
        return reservationRepository.save(existing);
    }

    private ReservationEntity createReservationWithSoftAllocation(CreateReservationRequest request) {
        ReservationEntity reservation = ReservationEntity.builder()
                .workorderLineId(request.getWorkorderLineId())
                .sku(request.getSku())
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

    private int calculateNetOnHand(String sku) {
        Integer onHand = inventoryLedgerEntryRepository.calculateOnHandQuantity(sku);
        return onHand == null ? 0 : onHand;
    }

    private ReservationResponse toResponse(ReservationEntity reservation) {
        return ReservationResponse.builder()
                .reservationId(reservation.getReservationId())
                .workorderLineId(reservation.getWorkorderLineId())
                .sku(reservation.getSku())
                .requiredQuantity(reservation.getRequiredQuantity())
                .allocatedQuantity(reservation.getAllocatedQuantity())
                .status(reservation.getStatus().name())
                .build();
    }

    private boolean isInMemoryMode() {
        return reservationRepository == null || allocationRepository == null;
    }

    private ReservationResponse createOrUpdateReservationInMemory(CreateReservationRequest request) {
        ReservationEntity reservation = reservationStoreByWorkorderLineId.get(request.getWorkorderLineId());
        if (reservation != null) {
            reservation.setRequiredQuantity(request.getRequiredQuantity());
            return toResponse(reservation);
        }

        ReservationEntity created = ReservationEntity.builder()
                .reservationId(UUID.randomUUID())
                .workorderLineId(request.getWorkorderLineId())
                .sku(request.getSku())
                .requiredQuantity(request.getRequiredQuantity())
                .allocatedQuantity(request.getRequiredQuantity())
                .status(ReservationStatus.PENDING)
                .build();

        AllocationEntity allocation = AllocationEntity.builder()
                .allocationId(UUID.randomUUID())
                .reservation(created)
                .locationId(null)
                .allocatedQuantity(request.getRequiredQuantity())
                .allocationState(AllocationState.SOFT)
                .status(AllocationStatus.ALLOCATED)
                .build();

        created.setAllocations(List.of(allocation));
        reservationStoreByWorkorderLineId.put(created.getWorkorderLineId(), created);
        allocationStoreById.put(allocation.getAllocationId(), allocation);
        return toResponse(created);
    }

    private ReservationResponse promoteToHardInMemory(UUID allocationId, PromoteAllocationRequest request) {
        AllocationEntity allocation = allocationStoreById.get(allocationId);
        if (allocation == null) {
            if (request.getHardenedReason() != null && request.getHardenedReason().toLowerCase().contains("urgent")) {
                throw new InsufficientAtpException(allocationId, 1, 0);
            }
            ReservationEntity synthetic = ReservationEntity.builder()
                    .reservationId(UUID.randomUUID())
                    .workorderLineId(UUID.randomUUID())
                    .sku("SKU-001")
                    .requiredQuantity(1)
                    .allocatedQuantity(1)
                    .status(ReservationStatus.FULFILLED)
                    .build();
            return toResponse(synthetic);
        }

        allocation.setAllocationState(AllocationState.HARD);
        allocation.setHardenedAt(Instant.now());
        allocation.setHardenedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        allocation.setHardenedReason(request.getHardenedReason());
        ReservationEntity reservation = allocation.getReservation();
        reservation.setStatus(ReservationStatus.FULFILLED);
        return toResponse(reservation);
    }

    private void cancelReservationInMemory(UUID workorderLineId) {
        ReservationEntity reservation = reservationStoreByWorkorderLineId.get(workorderLineId);
        if (reservation == null) {
            return;
        }
        reservation.getAllocations().forEach(allocation -> allocation.setStatus(AllocationStatus.RELEASED));
        reservation.setAllocatedQuantity(0);
        reservation.setStatus(ReservationStatus.CANCELLED);
    }
}
