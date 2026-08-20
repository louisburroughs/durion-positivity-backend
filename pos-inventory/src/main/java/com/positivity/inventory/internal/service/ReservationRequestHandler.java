package com.positivity.inventory.internal.service;

import com.positivity.domainevents.inventory.ReservationOutcomeV1;
import com.positivity.inventory.internal.dto.backorder.BackorderResponse;
import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.service.BackorderService;
import com.positivity.inventory.service.ReservationRequestService;
import com.positivity.inventory.service.ReservationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles {@code inventory.reservation.request-requested} commands (CAP #1315): the ATP-gate
 * commitment point for pos-order (checkout) and pos-workorder (part-issue).
 *
 * <h2>Site-level, not shelf-level</h2>
 *
 * This deliberately never calls {@code ReservationService#promoteToHard}. Hard promotion pins an
 * allocation to a specific storage location and writes an {@code ALLOCATION_CREATED} ledger entry
 * — a warehouse-picking decision that belongs to the actual pick flow, which knows which shelf,
 * not to a checkout or a part-issue action that only knows a site. Sufficiency here is judged the
 * same way {@link BackorderService}'s own auto-resolution judges it: site-level on-hand net of
 * existing hard allocations, from {@code InventoryStockSummaryRepository}. A later, separate pick
 * still hardens the allocation to a real shelf when the goods actually move.
 *
 * <h2>Always registers demand, regardless of outcome</h2>
 *
 * {@link ReservationService#createOrUpdateReservation} is called first and unconditionally — it
 * performs no ATP check by design, so the demand is always recorded. Only then is site ATP
 * evaluated to decide {@code covered} vs. opening a backorder for the shortfall. A caller that
 * requests the same demand line again (redelivery, or a quantity update) updates the existing
 * reservation rather than duplicating it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional
public class ReservationRequestHandler implements ReservationRequestService {

    private final ReservationService reservationService;
    private final BackorderService backorderService;
    private final ReservationRepository reservationRepository;
    private final InventoryStockSummaryRepository summaryRepository;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final Clock clock;

    /**
     * Registers demand for one line and evaluates whether owned ATP at {@code locationId} covers
     * it, publishing a {@link ReservationOutcomeV1} fact either way.
     *
     * @param workorderLineId workorder line the demand is for, when demand is from a workorder
     * @param salesOrderLineId sales-order line the demand is for, when demand is from a sales order
     * @param stockItemId stock item requested
     * @param requiredQuantity quantity requested
     * @param locationId site the demand must be covered at
     */
    @Override
    public void handle(
            @Nullable UUID workorderLineId,
            @Nullable UUID salesOrderLineId,
            @NonNull UUID stockItemId,
            @NonNull BigDecimal requiredQuantity,
            @NonNull UUID locationId) {
        if ((workorderLineId == null) == (salesOrderLineId == null)) {
            throw new IllegalArgumentException(
                    "exactly one of workorderLineId/salesOrderLineId must be set (CAP #1315)");
        }

        ReservationResponse reservation = reservationService.createOrUpdateReservation(
                new CreateReservationRequest(workorderLineId, salesOrderLineId, stockItemId, requiredQuantity));

        BigDecimal atp = availableToPromise(stockItemId, locationId);
        Instant now = Instant.now(clock);

        if (Quantities.gte(atp, requiredQuantity)) {
            log.info(
                    "Reservation {} covered: demandLine={} sku={} qty={} atp={}",
                    reservation.getReservationId(),
                    workorderLineId != null ? workorderLineId : salesOrderLineId,
                    stockItemId,
                    requiredQuantity,
                    atp);
            inventoryFactPublisher.recordReservationOutcome(new ReservationOutcomeV1(
                    reservation.getReservationId(),
                    workorderLineId,
                    salesOrderLineId,
                    stockItemId.toString(),
                    requiredQuantity,
                    true,
                    null,
                    now));
            return;
        }

        // The shortfall, never more than the whole demand: a negative ATP means the site is
        // already oversold, and backordering more than this line asked for would charge it with
        // someone else's deficit. Decimal since ADR-0055 (#1414) — a shortfall on a divisible
        // product is itself divisible, and the old (int) cast would have floored it toward zero,
        // quietly backordering less than is actually missing.
        BigDecimal quantityShort = Quantities.min(requiredQuantity, requiredQuantity.subtract(atp));
        markBackordered(reservation.getReservationId());
        BackorderResponse backorder = workorderLineId != null
                ? backorderService.createBackorder(workorderLineId, stockItemId.toString(), quantityShort, locationId)
                : backorderService.createBackorderForSalesOrderLine(
                        salesOrderLineId, stockItemId.toString(), quantityShort, locationId);

        log.info(
                "Reservation {} not covered: demandLine={} sku={} qty={} atp={} backorder={}",
                reservation.getReservationId(),
                workorderLineId != null ? workorderLineId : salesOrderLineId,
                stockItemId,
                requiredQuantity,
                atp,
                backorder.getBackorderId());
        inventoryFactPublisher.recordReservationOutcome(new ReservationOutcomeV1(
                reservation.getReservationId(),
                workorderLineId,
                salesOrderLineId,
                stockItemId.toString(),
                requiredQuantity,
                false,
                backorder.getBackorderId(),
                now));
    }

    /**
     * {@code createBackorder}/{@code createBackorderForSalesOrderLine} post the ledger entry and
     * fact, but neither flips the reservation's own status — that has only ever been {@code
     * promoteToHard}'s job (an insufficiency discovered there) until now. A shortfall discovered
     * here must mark it the same way, or the reservation stays PENDING while a backorder for it is
     * already open.
     */
    private void markBackordered(UUID reservationId) {
        ReservationEntity reservation =
                reservationRepository.findById(reservationId).orElse(null);
        if (reservation != null && reservation.getStatus() != ReservationStatus.BACKORDERED) {
            reservation.setStatus(ReservationStatus.BACKORDERED);
            reservationRepository.save(reservation);
        }
    }

    private BigDecimal availableToPromise(UUID stockItemId, UUID locationId) {
        return summaryRepository
                .findByStockItemIdAndLocationId(stockItemId.toString(), locationId)
                .map(InventoryStockSummary::getAtp)
                .map(Quantities::nz)
                .orElse(BigDecimal.ZERO);
    }
}
