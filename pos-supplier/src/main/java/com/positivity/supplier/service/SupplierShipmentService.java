package com.positivity.supplier.service;

import com.positivity.supplier.service.model.ShipmentEventView;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read surface over vendor shipment-tracking events (ADR-0026 contract).
 *
 * <p>Shipment milestones flow to pos-order as the append-only {@code supplier.shipment.event}
 * fact on the purchase-order timeline (ADR-0049 §3); this interface exposes the tracking
 * ledger for the module's own frontend-facing controllers.
 *
 * <p>Placeholder-level for the CAP-317 foundation slice; implementation arrives with CAP-322
 * (Stock Report + Shipment tracking, durion#377).
 */
public interface SupplierShipmentService {

    /**
     * Returns the shipment events recorded for a vendor order reference.
     *
     * @param vendorProfileId vendor profile identity (UUIDv7, ADR-0050 §1)
     * @param orderReference vendor order reference the events belong to
     * @return tracking events in occurrence order; empty when none
     */
    @NonNull
    List<ShipmentEventView> findShipmentEvents(@NonNull UUID vendorProfileId, @NonNull String orderReference);
}
