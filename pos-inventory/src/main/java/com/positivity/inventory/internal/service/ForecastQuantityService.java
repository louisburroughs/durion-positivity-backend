package com.positivity.inventory.internal.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
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
            @NonNull String stockItemId, @Nullable UUID siteId, @Nullable Instant horizon, @NonNull BigDecimal onHand);

    /**
     * Computed forecast triple.
     *
     * <p>Decimal since ADR-0055 (#1414). These derive from the same arithmetic as
     * {@code onHand} — {@code projectedAvailable = onHand + incoming - outgoing} — so leaving them
     * integral once on-hand is decimal would only relocate the truncation into the projection,
     * where it would be harder to see. The former {@code DOWN} floor on incoming supply went with
     * the widening: it existed to squeeze a fractional expected quantity into an integer field
     * without over-promising, and there is no longer a field to squeeze it into.
     */
    /**
     * {@link #forecast} summed over a set of sites, for an availability view narrowed to the
     * caller's location reach (ADR-0061 §3, #1872). Site-bound supply and pick demand are summed
     * per site; open reservation demand is SKU-wide (as it is for a single site) and counted once.
     * An empty set forecasts nothing: incoming and outgoing are zero and projected is {@code onHand}.
     *
     * @param stockItemId the SKU
     * @param siteIds the reachable sites; may be empty
     * @param horizon optional forecast horizon
     * @param onHand the on-hand already summed over the same sites
     * @return the combined forecast
     */
    @NonNull
    ForecastQuantities forecastWithin(
            @NonNull String stockItemId,
            @NonNull Set<UUID> siteIds,
            @Nullable Instant horizon,
            @NonNull BigDecimal onHand);

    record ForecastQuantities(BigDecimal incomingQty, BigDecimal outgoingQty, BigDecimal projectedAvailable) {}
}
