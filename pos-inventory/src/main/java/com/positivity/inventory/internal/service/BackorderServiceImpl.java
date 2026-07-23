package com.positivity.inventory.internal.service;

import com.positivity.domainevents.inventory.BackorderCreatedV1;
import com.positivity.domainevents.inventory.BackorderResolvedV1;
import com.positivity.inventory.internal.dto.backorder.BackorderResponse;
import com.positivity.inventory.internal.entity.BackorderRecord;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.BackorderResolutionSource;
import com.positivity.inventory.internal.enums.BackorderStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.BackorderRecordRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.service.BackorderService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backorder lifecycle (odoo-parity G1, issue #1046).
 *
 * <p><b>Creation</b> ({@link #createBackorder}): persists an OPEN {@link BackorderRecord} and posts
 * the ATP-neutral {@code BACKORDER_CREATED} ledger entry ({@code changeInQuantity = quantityShort},
 * {@code sourceTransactionId = backorderId}) through the single funnel, then queues the
 * {@code BackorderCreatedV1} occurrence fact. Idempotent per {@code (workorderLineId, sku)} — an
 * existing OPEN backorder is returned unchanged (the V24 partial unique index is the cross-instance
 * backstop).
 *
 * <p><b>Auto-resolution</b> ({@link #onInboundAvailability}, the {@link BackorderResolutionTrigger}
 * seam): after any inbound on-hand posting the ledger funnel calls this for the touched (SKU, site).
 * OPEN backorders there are resolved <em>oldest-priority-first</em>, reusing the
 * {@code AllocationReallocationServiceImpl} fairness ordering (effective priority with 24h aging,
 * then dueDate / waitingSince / scheduleStart / createdAt) against each backorder's backing
 * reservation, falling back to backorder {@code createdAt ASC} when no reservation is linked.
 * Coverage is gated on current ATP at the site: a backorder resolves only when the full
 * {@code quantityShort} fits the remaining budget, which is decremented per resolution — so two
 * competing backorders resolve older-first when only one can be covered, and a larger shortage is
 * left OPEN under partial availability. Resolution flips the backing reservation out of BACKORDERED
 * back to PENDING (normal fulfillment re-drives it); it does not itself consume or allocate stock.
 *
 * <p><b>Idempotency</b>: only OPEN backorders are candidates, so a replayed inbound signal finds an
 * already-RESOLVED backorder gone from the candidate set and never resolves it twice; CANCELLED
 * backorders are terminal and never auto-resolve. {@code BACKORDER_RESOLVED} is ATP-neutral, so it
 * cannot re-enter the inbound trigger.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class BackorderServiceImpl implements BackorderService, BackorderResolutionTrigger {

    /** Mirror of {@code AllocationReallocationServiceImpl}'s priority-aging constants. */
    private static final Duration AGING_GRACE_PERIOD = Duration.ofHours(24);

    private static final Duration AGING_INTERVAL = Duration.ofHours(24);
    private static final int CRITICAL_PRIORITY = 1;
    private static final int DEFAULT_PRIORITY = 5;
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final BackorderRecordRepository backorderRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryLedgerEntryRepository ledgerRepository;
    private final InventoryStockSummaryRepository summaryRepository;
    private final LedgerPostingService ledgerPostingService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final Clock clock;

    @Override
    public @NonNull BackorderResponse createBackorder(
            @NonNull UUID workorderLineId, @NonNull String sku, int quantityShort, @Nullable UUID locationId) {
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantityShort <= 0) {
            throw new IllegalArgumentException("quantityShort must be positive");
        }

        Optional<BackorderRecord> existingOpen =
                backorderRepository.findByWorkorderLineIdAndSkuAndStatus(workorderLineId, sku, BackorderStatus.OPEN);
        if (existingOpen.isPresent()) {
            log.info("Backorder already OPEN for workorderLine {} sku {}; returning existing", workorderLineId, sku);
            return toResponse(existingOpen.get());
        }

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
        BackorderRecord backorder = backorderRepository.save(BackorderRecord.builder()
                .workorderLineId(workorderLineId)
                .sku(sku)
                .locationId(locationId)
                .quantityShort(quantityShort)
                .status(BackorderStatus.OPEN)
                .createdBy(actor)
                .build());

        InventoryLedgerEntry created = postNeutralEntry(
                InventoryLedgerEventType.BACKORDER_CREATED, backorder, quantityShort, locationId, actor);
        backorder.setCreatedLedgerEntryId(created.getLedgerEntryId());
        backorder = backorderRepository.save(backorder);

        inventoryFactPublisher.recordBackorderCreated(new BackorderCreatedV1(
                backorder.getBackorderId(), workorderLineId, sku, quantityShort, locationId, Instant.now(clock)));

        log.info(
                "Backorder {} opened for workorderLine {} sku {} qtyShort {} at {}",
                backorder.getBackorderId(),
                workorderLineId,
                sku,
                quantityShort,
                locationId);
        return toResponse(backorder);
    }

    @Override
    public void onInboundAvailability(@NonNull String sku, @Nullable UUID locationId) {
        if (locationId == null || sku.isBlank()) {
            return;
        }
        List<BackorderRecord> openBackorders = backorderRepository.findBySkuAndLocationIdAndStatusOrderByCreatedAtAsc(
                sku, locationId, BackorderStatus.OPEN);
        if (openBackorders.isEmpty()) {
            return;
        }

        Instant now = Instant.now(clock);
        Map<UUID, ReservationEntity> reservations = loadBackingReservations(openBackorders);
        List<BackorderRecord> ordered = openBackorders.stream()
                .sorted(oldestPriorityFirst(reservations, now))
                .toList();

        long budget = availableToPromise(sku, locationId);
        for (BackorderRecord backorder : ordered) {
            int shortage = backorder.getQuantityShort();
            if (shortage > budget) {
                // Partial availability: whole-backorder resolution only — leave larger shortages OPEN.
                continue;
            }
            try {
                resolve(backorder, reservations.get(backorder.getWorkorderLineId()), now);
                budget -= shortage;
            } catch (RuntimeException e) {
                // Best-effort per candidate: one bad backorder must not roll back the inbound posting.
                log.warn("Failed to auto-resolve backorder {}: {}", backorder.getBackorderId(), e.getMessage());
            }
        }
    }

    private void resolve(BackorderRecord backorder, @Nullable ReservationEntity reservation, Instant now) {
        InventoryLedgerEntry resolved = postNeutralEntry(
                InventoryLedgerEventType.BACKORDER_RESOLVED,
                backorder,
                backorder.getQuantityShort(),
                backorder.getLocationId(),
                SYSTEM_ACTOR);

        backorder.setStatus(BackorderStatus.RESOLVED);
        backorder.setResolutionSource(BackorderResolutionSource.AVAILABILITY);
        backorder.setResolvedAt(now);
        backorder.setResolvedLedgerEntryId(resolved.getLedgerEntryId());
        backorderRepository.save(backorder);

        // Re-open the backing reservation so normal promotion re-drives fulfillment now that stock
        // is available; resolution itself neither consumes nor allocates stock.
        if (reservation != null && reservation.getStatus() == ReservationStatus.BACKORDERED) {
            reservation.setStatus(ReservationStatus.PENDING);
            reservationRepository.save(reservation);
        }

        inventoryFactPublisher.recordBackorderResolved(new BackorderResolvedV1(
                backorder.getBackorderId(),
                backorder.getWorkorderLineId(),
                backorder.getSku(),
                backorder.getQuantityShort(),
                BackorderResolutionSource.AVAILABILITY.name(),
                backorder.getLocationId(),
                now));

        log.info(
                "Backorder {} auto-resolved (AVAILABILITY) for workorderLine {} sku {} qty {} at {}",
                backorder.getBackorderId(),
                backorder.getWorkorderLineId(),
                backorder.getSku(),
                backorder.getQuantityShort(),
                backorder.getLocationId());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull BackorderResponse getBackorder(@NonNull UUID backorderId) {
        return toResponse(backorderRepository
                .findById(backorderId)
                .orElseThrow(() -> new ResourceNotFoundException("Backorder", backorderId.toString())));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<BackorderResponse> listBackorders(
            @Nullable BackorderStatus status,
            @Nullable String sku,
            @Nullable UUID locationId,
            @Nullable UUID workorderLineId) {
        Specification<BackorderRecord> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (sku != null && !sku.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("sku"), sku));
        }
        if (locationId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("locationId"), locationId));
        }
        if (workorderLineId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("workorderLineId"), workorderLineId));
        }
        return backorderRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Posts an ATP-neutral backorder ledger entry ({@code BACKORDER_CREATED}/{@code
     * BACKORDER_RESOLVED}). {@code quantityAfter} carries the unchanged on-hand at the site for
     * audit continuity — the entry does not affect on-hand or ATP (verified: {@code
     * !eventType.affectsOnHand()}), so the summary is untouched and the entry cannot re-trigger the
     * inbound auto-resolution seam.
     */
    private InventoryLedgerEntry postNeutralEntry(
            InventoryLedgerEventType eventType,
            BackorderRecord backorder,
            int quantityShort,
            @Nullable UUID locationId,
            String actor) {
        int onHand = locationId == null
                ? 0
                : Optional.ofNullable(
                                ledgerRepository.calculateOnHandQuantityAtLocation(backorder.getSku(), locationId))
                        .orElse(0);
        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId(backorder.getSku())
                .eventType(eventType)
                .changeInQuantity(quantityShort)
                .quantityAfter(onHand)
                .locationId(locationId)
                .sourceTransactionId(backorder.getBackorderId().toString())
                .transactionUserId(actor)
                .build();
        return ledgerPostingService.post(entry);
    }

    private long availableToPromise(String sku, UUID locationId) {
        return summaryRepository
                .findByStockItemIdAndLocationId(sku, locationId)
                .map(row -> row.getAtp())
                .orElse(0L);
    }

    private Map<UUID, ReservationEntity> loadBackingReservations(List<BackorderRecord> backorders) {
        Map<UUID, ReservationEntity> byWorkorderLine = new HashMap<>();
        for (BackorderRecord backorder : backorders) {
            byWorkorderLine.computeIfAbsent(
                    backorder.getWorkorderLineId(),
                    lineId ->
                            reservationRepository.findByWorkorderLineId(lineId).orElse(null));
        }
        return byWorkorderLine;
    }

    /**
     * Oldest-priority-first ordering, reusing {@code AllocationReallocationServiceImpl}'s fairness
     * comparator against each backorder's backing reservation. Backorders with no linked reservation
     * fall back to a default priority and their own {@code createdAt}; the final tie-breaker is
     * always the backorder's {@code createdAt} (older wins).
     */
    private Comparator<BackorderRecord> oldestPriorityFirst(Map<UUID, ReservationEntity> reservations, Instant now) {
        return Comparator.comparingInt(
                        (BackorderRecord b) -> effectivePriority(reservations.get(b.getWorkorderLineId()), now))
                .thenComparing(b -> dueDate(reservations.get(b.getWorkorderLineId())))
                .thenComparing(b -> waitingSince(reservations.get(b.getWorkorderLineId()), b))
                .thenComparing(b -> scheduleStart(reservations.get(b.getWorkorderLineId())))
                .thenComparing(BackorderRecord::getCreatedAt);
    }

    private int effectivePriority(@Nullable ReservationEntity reservation, Instant now) {
        if (reservation == null) {
            return DEFAULT_PRIORITY;
        }
        if (reservation.getWaitingSince() == null) {
            return reservation.getPriority();
        }
        Duration ageable = Duration.between(reservation.getWaitingSince(), now).minus(AGING_GRACE_PERIOD);
        if (ageable.isNegative()) {
            return reservation.getPriority();
        }
        long agingSteps = ageable.dividedBy(AGING_INTERVAL);
        return Math.max(CRITICAL_PRIORITY, (int) (reservation.getPriority() - agingSteps));
    }

    private Instant dueDate(@Nullable ReservationEntity reservation) {
        if (reservation == null || reservation.getDueDateTime() == null) {
            return Instant.MAX;
        }
        return reservation.getDueDateTime();
    }

    private Instant waitingSince(@Nullable ReservationEntity reservation, BackorderRecord backorder) {
        if (reservation != null && reservation.getWaitingSince() != null) {
            return reservation.getWaitingSince();
        }
        return backorder.getCreatedAt();
    }

    private Instant scheduleStart(@Nullable ReservationEntity reservation) {
        if (reservation == null || reservation.getScheduleStartTime() == null) {
            return Instant.MAX;
        }
        return reservation.getScheduleStartTime();
    }

    private BackorderResponse toResponse(BackorderRecord backorder) {
        return BackorderResponse.builder()
                .backorderId(backorder.getBackorderId())
                .workorderLineId(backorder.getWorkorderLineId())
                .sku(backorder.getSku())
                .locationId(backorder.getLocationId())
                .quantityShort(backorder.getQuantityShort())
                .status(backorder.getStatus())
                .resolutionSource(backorder.getResolutionSource())
                .createdBy(backorder.getCreatedBy())
                .createdAt(backorder.getCreatedAt())
                .updatedAt(backorder.getUpdatedAt())
                .resolvedAt(backorder.getResolvedAt())
                .build();
    }
}
