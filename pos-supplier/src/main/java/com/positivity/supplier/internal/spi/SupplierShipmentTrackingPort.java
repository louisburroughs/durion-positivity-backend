package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierExchange;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierShipmentEvent;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: shipment tracking events for a vendor order
 * ({@code SHIPMENT_TRACKING}; LEX Shipment Tracking v1).
 *
 * <p>Governing ADRs: ADR-0049 §3 — feeds the append-only {@code supplier.shipment.event}
 * fact on the purchase-order timeline in pos-order.
 */
public interface SupplierShipmentTrackingPort {

    /** Fetches the tracking events the vendor has recorded for the given order reference. */
    @NonNull
    SupplierExchange<List<SupplierShipmentEvent>> fetchTrackingEvents(
            @NonNull SupplierRef supplierRef, @NonNull PartyContext partyContext, @NonNull String orderReference);
}
