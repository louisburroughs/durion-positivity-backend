package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.AsnStatus;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.repository.AsnLineRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Approximates the stock-out deadline for a triggered replenishment policy (odoo-parity F3,
 * issue #1041; spec F3 — Odoo orderpoint deadline analogue): the earliest date at which the
 * horizon-bounded {@code projectedAvailable} goes below zero.
 *
 * <p><b>Approximation (documented, deliberate):</b> the projection is piecewise-constant in
 * the horizon — it only changes at document date cutoffs (PO expected-delivery dates, ASN
 * expected-arrival dates, reservation due instants). So instead of walking every day, the
 * calculator evaluates {@link ForecastQuantityService#forecast} at exactly those candidate
 * cutoffs within {@code (now, leadHorizon]}, ascending, and returns the UTC date of the
 * first one whose projection is negative. If the projection is already negative at
 * {@code now}, the deadline is today. Limitations inherited from the horizon rule: undated
 * documents are excluded from every bounded evaluation (they can neither cause nor avert a
 * dated stock-out), and released pick-task demand is horizon-independent, so it shifts the
 * whole curve rather than creating a cutoff. Returns {@code null} when the projection never
 * dips below zero within the lead horizon (a below-min breach without a projected
 * stock-out has no deadline).
 */
@Component
@RequiredArgsConstructor
public class StockoutDeadlineCalculator {

    // Mirrors the open-document status sets of ExpectedSupplyServiceImpl /
    // ForecastQuantityServiceImpl: candidate cutoffs must come from exactly the documents
    // the forecast counts, or the walk would evaluate at dates where nothing changes (or
    // miss dates where something does).
    private static final List<PurchaseOrderStatus> OPEN_PO_STATUSES =
            List.of(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED);
    private static final List<AsnStatus> OPEN_ASN_STATUSES =
            List.of(AsnStatus.LOADED, AsnStatus.READY_FOR_RECEIPT, AsnStatus.PARTIALLY_RECEIVED);
    private static final List<ReservationStatus> OPEN_RESERVATION_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.PARTIALLY_FULFILLED);

    private final ForecastQuantityService forecastQuantityService;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final AsnLineRepository asnLineRepository;
    private final ReservationRepository reservationRepository;

    /**
     * Earliest UTC date within {@code (now, leadHorizon]} at which the projection for the
     * SKU goes below zero, today when it is already negative at {@code now}, or
     * {@code null} when it never does.
     *
     * @param stockItemId ledger stock-item identifier (product UUID string for PO lines)
     * @param forecastSiteId site scope for supply documents (bin already resolved to site)
     * @param onHand current on-hand at the policy's own location
     * @param now evaluation instant
     * @param leadHorizon the policy's resolved lead-time horizon (walk upper bound)
     */
    public @Nullable LocalDate stockoutDeadline(
            @NonNull String stockItemId,
            @Nullable UUID forecastSiteId,
            long onHand,
            @NonNull Instant now,
            @NonNull Instant leadHorizon) {
        if (projectedAt(stockItemId, forecastSiteId, onHand, now) < 0) {
            return LocalDate.ofInstant(now, ZoneOffset.UTC);
        }
        for (Instant cutoff : candidateCutoffs(stockItemId, forecastSiteId, now, leadHorizon)) {
            if (projectedAt(stockItemId, forecastSiteId, onHand, cutoff) < 0) {
                return LocalDate.ofInstant(cutoff, ZoneOffset.UTC);
            }
        }
        return null;
    }

    private long projectedAt(String stockItemId, @Nullable UUID forecastSiteId, long onHand, Instant horizon) {
        return forecastQuantityService
                .forecast(stockItemId, forecastSiteId, horizon, onHand)
                .projectedAvailable();
    }

    /**
     * Distinct document cutoff instants in {@code (now, leadHorizon]}, ascending. Date-only
     * document dates (PO/ASN headers) become start-of-day UTC instants — the horizon rule
     * compares by UTC date, so any instant on the day includes the day's documents;
     * reservation due instants are used exactly.
     */
    private TreeSet<Instant> candidateCutoffs(
            String stockItemId, @Nullable UUID forecastSiteId, Instant now, Instant leadHorizon) {
        LocalDate horizonDate = LocalDate.ofInstant(leadHorizon, ZoneOffset.UTC);
        TreeSet<Instant> cutoffs = new TreeSet<>();

        UUID skuUuid = parseUuid(stockItemId);
        if (skuUuid != null) {
            purchaseOrderLineRepository
                    .findDistinctExpectedDeliveryDatesForSku(skuUuid, OPEN_PO_STATUSES, forecastSiteId, horizonDate)
                    .forEach(date ->
                            cutoffs.add(date.atStartOfDay(ZoneOffset.UTC).toInstant()));
            reservationRepository
                    .findDistinctDueInstantsForSku(skuUuid, OPEN_RESERVATION_STATUSES, leadHorizon)
                    .forEach(cutoffs::add);
        }
        asnLineRepository
                .findDistinctExpectedArrivalDatesForSku(stockItemId, OPEN_ASN_STATUSES, forecastSiteId, horizonDate)
                .forEach(date -> cutoffs.add(date.atStartOfDay(ZoneOffset.UTC).toInstant()));

        // Keep only cutoffs strictly after "now" (the now-projection is evaluated first) and
        // never beyond the lead horizon. Single subSet call: with a 0-day lead time
        // now == leadHorizon and chained tailSet/headSet views would reject the bound.
        return new TreeSet<>(cutoffs.subSet(now, false, leadHorizon, true));
    }

    private static @Nullable UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
