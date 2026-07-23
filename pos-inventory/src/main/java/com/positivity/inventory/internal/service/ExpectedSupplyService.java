package com.positivity.inventory.internal.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Open expected supply per SKU × site (odoo-parity A2, issue #1028).
 *
 * <p>Deliberately its own query surface so later parity stories extend supply sources
 * additively — parity-C2 adds inbound transfer orders in transit to this computation without
 * touching the availability read path.
 */
public interface ExpectedSupplyService {

    /**
     * Sum of open expected supply for one stock item.
     *
     * <p>v1 sources (spec A2): approved-PO open line quantity ({@code openQuantityDecimal} of
     * purchase orders in status APPROVED or PARTIALLY_RECEIVED) plus un-received ASN remainder
     * ({@code quantityShipped - quantityReceived} of ASNs in status LOADED, READY_FOR_RECEIPT,
     * or PARTIALLY_RECEIVED). Note that goods shipped under an ASN but not yet received remain
     * counted by both sources until receipt posts (spec-mandated open-supply definition).
     *
     * <p><b>Horizon rule:</b> when {@code horizon} is non-null, only supply expected on or
     * before it counts — PO lines by the header {@code expectedDeliveryDate}, ASN lines by the
     * header {@code expectedArrivalDate} (both {@code LocalDate}s, compared against the horizon
     * instant's UTC date). Documents with a {@code NULL} expected date are INCLUDED in the
     * unbounded variant and EXCLUDED from the horizon-bounded variant: undated supply exists
     * but cannot be promised by any date.
     *
     * @param stockItemId ledger stock-item identifier (product UUID string for PO lines; also
     *     matched verbatim against the ASN line {@code sku})
     * @param siteId optional ship-to site scope; {@code null} sums across all sites
     * @param horizon optional supply-date cutoff (see horizon rule above)
     * @return non-negative open supply quantity in the product's base UoM
     */
    @NonNull
    BigDecimal expectedIncomingQuantity(@NonNull String stockItemId, @Nullable UUID siteId, @Nullable Instant horizon);
}
