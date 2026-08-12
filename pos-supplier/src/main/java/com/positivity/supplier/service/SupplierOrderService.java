package com.positivity.supplier.service;

import com.positivity.supplier.service.model.OrderTransmissionStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read surface over purchase-order <em>transmission</em> state (ADR-0026 contract, ADR-0049 §2).
 *
 * <p>pos-supplier holds transmission/exchange state only — the purchase-order aggregate stays in
 * pos-order, and order create/status flows are event-driven per ADR-0049 §3
 * ({@code supplier.order.requested} / {@code supplier.order.confirmed} / {@code
 * supplier.order.rejected} / {@code supplier.orderstatus.changed}). This interface exposes the
 * transmission ledger for the module's own frontend-facing controllers, not a synchronous
 * cross-module surface.
 *
 * <p>Placeholder-level for the CAP-317 foundation slice; implementation arrives with CAP-320
 * (Order Create + Order Status, durion#375).
 */
public interface SupplierOrderService {

    /**
     * Returns the transmission states recorded for a purchase order, one per transmission
     * intent (initial, revision, replacement, split — ADR-0052 §1).
     *
     * @param purchaseOrderId originating pos-order purchase-order aggregate id
     * @return transmission states, newest intent first; empty when nothing was ever transmitted
     */
    @NonNull
    List<OrderTransmissionStatus> findTransmissionsByPurchaseOrderId(@NonNull UUID purchaseOrderId);
}
