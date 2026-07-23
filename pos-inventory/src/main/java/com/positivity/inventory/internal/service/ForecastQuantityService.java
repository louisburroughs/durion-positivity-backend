package com.positivity.inventory.internal.service;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Odoo-style forecast quantities per SKU × site (odoo-parity A2, issue #1028; Odoo
 * {@code incoming_qty} / {@code outgoing_qty} / {@code virtual_available}).
 *
 * <p>These are computed on read from open documents — never stored on
 * {@code inventory_stock_summary}, which derives exclusively from the ledger.
 */
public interface ForecastQuantityService {

    /**
     * Computes incoming/outgoing/projected quantities for one stock item.
     *
     * <p>{@code incomingQty} comes from {@link ExpectedSupplyService} (open PO + un-received
     * ASN supply). {@code outgoingQty} sums open demand not yet decremented from on-hand:
     * <ul>
     *   <li>reservation remainders ({@code requiredQuantity - allocatedQuantity}, floored at
     *       zero) of PENDING / PARTIALLY_FULFILLED reservations — reservations carry no site,
     *       so this component is site-agnostic and included in every scope (conservative:
     *       unattributed demand lowers each site's projection rather than vanishing);</li>
     *   <li>released-not-picked pick-task remainders ({@code quantityRequired -
     *       quantityPicked}) of PENDING tasks in released (READY_TO_PICK / IN_PROGRESS) pick
     *       lists, site-scoped via the task's suggested location and the storage-location
     *       replica.</li>
     * </ul>
     *
     * <p>{@code projectedAvailable = onHand + incomingQty - outgoingQty} (Odoo
     * {@code virtual_available}).
     *
     * <p><b>Horizon rule:</b> a non-null {@code horizon} bounds incoming supply by expected
     * PO-delivery / ASN-arrival dates and reservation demand by {@code dueDateTime}; documents
     * with {@code NULL} dates are included unbounded and excluded when bounded (see
     * {@link ExpectedSupplyService#expectedIncomingQuantity}). Released pick tasks are
     * imminent, already-released floor demand and count in both variants regardless of
     * {@code dueAt}.
     *
     * @param stockItemId ledger stock-item identifier
     * @param siteId optional site scope; {@code null} computes across all sites
     * @param horizon optional date cutoff
     * @param onHand the on-hand quantity of the same scope, used for the projection
     */
    @NonNull
    ForecastQuantities forecast(
            @NonNull String stockItemId, @Nullable UUID siteId, @Nullable Instant horizon, long onHand);

    /** Computed forecast triple; quantities floor fractional supply DOWN (never over-promise). */
    record ForecastQuantities(long incomingQty, long outgoingQty, long projectedAvailable) {}
}
