package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.reallocation.ReallocateRequest;
import com.positivity.inventory.internal.dto.reallocation.ReallocateResponse;
import com.positivity.inventory.internal.entity.AllocationAuditEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationAuditReasonCode;
import com.positivity.inventory.internal.repository.AllocationAuditRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.inventory.service.AllocationReallocationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class AllocationReallocationServiceImpl implements AllocationReallocationService {

    private static final Duration AGING_GRACE_PERIOD = Duration.ofHours(24);
    private static final Duration AGING_INTERVAL = Duration.ofHours(24);
    private static final int CRITICAL_PRIORITY = 1;

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final AllocationAuditRepository allocationAuditRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    @Override
    public @NonNull ReallocateResponse reallocate(@NonNull ReallocateRequest request) {
        UUID stockItemId = request.getStockItemId();
        if (stockItemId == null) {
            throw new IllegalArgumentException("stockItemId is required");
        }
        Instant now = Instant.now(clock);

        int onHand = Optional.ofNullable(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId))
                .orElse(0);

        List<ReservationEntity> reservations = reservationRepository.findByStockItemId(stockItemId);

        // Sort by effective priority ASC (1=CRITICAL on a 1–10 scale, so ASC integer
        // order
        // gives highest-priority-first behaviour), then by the 4 fairness tie-breakers.
        List<ReservationEntity> sorted = reservations.stream()
                .sorted(Comparator
                        .comparingInt((ReservationEntity r) -> computeEffectivePriority(r, now))
                        .thenComparing(r -> Optional.ofNullable(r.getDueDateTime())
                                .orElse(Instant.MAX))
                        .thenComparing(r -> Optional.ofNullable(r.getWaitingSince())
                                .orElse(r.getCreatedAt()))
                        .thenComparing(r -> Optional.ofNullable(r.getScheduleStartTime())
                                .orElse(Instant.MAX))
                        .thenComparing(ReservationEntity::getCreatedAt))
                .toList();

        int previousTotalAllocated = reservations.stream()
                .mapToInt(ReservationEntity::getAllocatedQuantity)
                .sum();
        int remainingStock = previousTotalAllocated;
        int totalReallocated = 0;
        List<AllocationAuditEntity> audits = new ArrayList<>();

        for (ReservationEntity reservation : sorted) {
            int required = reservation.getRequiredQuantity();
            int prevAlloc = reservation.getAllocatedQuantity();
            if (remainingStock >= required) {
                reservation.setAllocatedQuantity(required);
                remainingStock -= required;
                totalReallocated++;
                AllocationAuditReasonCode reasonCode = parseReasonCode(request.getTriggerType());
                int effective = computeEffectivePriority(reservation, now);
                AllocationAuditReasonCode auditReason = (effective < reservation.getPriority())
                        ? AllocationAuditReasonCode.PRIORITY_AGED
                        : reasonCode;
                audits.add(buildAudit(request, auditReason, now, prevAlloc, required));
            } else {
                reservation.setAllocatedQuantity(0);
                audits.add(buildAudit(request, AllocationAuditReasonCode.STOCK_SHORTAGE, now,
                        prevAlloc, 0));
            }
        }

        reservationRepository.saveAll(sorted);
        allocationAuditRepository.saveAll(audits);

        int totalAllocated = sorted.stream()
                .mapToInt(ReservationEntity::getAllocatedQuantity)
                .sum();
        int atpAfterReallocation = onHand - totalAllocated;

        return ReallocateResponse.builder()
                .stockItemId(request.getStockItemId())
                .totalReallocated(totalReallocated)
                .auditRecordsCreated(audits.size())
                .atpAfterReallocation(atpAfterReallocation)
                .build();
    }

    private int computeEffectivePriority(ReservationEntity reservation, Instant now) {
        if (reservation.getWaitingSince() == null) {
            return reservation.getPriority();
        }
        Duration elapsed = Duration.between(reservation.getWaitingSince(), now);
        Duration ageable = elapsed.minus(AGING_GRACE_PERIOD);
        if (ageable.isNegative()) {
            return reservation.getPriority();
        }
        long agingSteps = ageable.dividedBy(AGING_INTERVAL);
        return Math.max(CRITICAL_PRIORITY, (int) (reservation.getPriority() - agingSteps));
    }

    private AllocationAuditEntity buildAudit(ReallocateRequest request,
            AllocationAuditReasonCode reasonCode, Instant now,
            int previousAllocatedQty, int newAllocatedQty) {
        return AllocationAuditEntity.builder()
                .stockItemId(request.getStockItemId())
                .reasonCode(reasonCode)
                .triggeredBy(resolveTriggeredBy())
                .triggerReferenceId(request.getTriggerReferenceId())
                .previousState("allocatedQuantity=" + previousAllocatedQty)
                .newState("allocatedQuantity=" + newAllocatedQty)
                .occurredAt(now)
                .build();
    }

    private String resolveTriggeredBy() {
        return SecurityContextHelper.getCurrentUsername()
                .filter(name -> !name.isBlank())
                .orElse("SYSTEM");
    }

    private AllocationAuditReasonCode parseReasonCode(String triggerType) {
        if (triggerType == null) {
            return AllocationAuditReasonCode.MANUAL_OVERRIDE;
        }
        try {
            return AllocationAuditReasonCode.valueOf(triggerType);
        } catch (IllegalArgumentException exception) {
            return AllocationAuditReasonCode.MANUAL_OVERRIDE;
        }
    }
}
