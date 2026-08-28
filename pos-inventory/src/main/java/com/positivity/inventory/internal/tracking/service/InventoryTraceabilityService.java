package com.positivity.inventory.internal.tracking.service;

import com.positivity.inventory.internal.dto.traceability.LotTraceabilityResponse;
import com.positivity.inventory.internal.dto.traceability.SerialTraceabilityResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read API assembling upstream/downstream traceability for a lot or serial unit (odoo-parity E5,
 * issue #1051).
 *
 * <p>Odoo renders a traceability tree per lot; Durion's append-only ledger plus the lot/serial
 * receipt-and-consumption linkages make this a query rather than a graph walk. The upstream side
 * resolves the PO/ASN/goods-receipt origin; the downstream side reconstructs the movement chain
 * (putaway → pick → consumption → return → scrap → transfer) from the lot-tagged ledger entries,
 * or, for a serial, from its receipt/consumption ledger linkages plus any warranty-hold join.
 */
public interface InventoryTraceabilityService {

    /**
     * Assembles the traceability tree for one lot.
     *
     * @param lotId the lot id
     * @return the lot's upstream origin and downstream movement chain
     */
    @NonNull
    LotTraceabilityResponse getLotTraceability(@NonNull UUID lotId);

    /**
     * Assembles the traceability tree for one serial unit.
     *
     * @param serialUnitId the serial unit id
     * @return the serial's upstream origin, downstream chain, and warranty holds
     */
    @NonNull
    SerialTraceabilityResponse getSerialTraceability(@NonNull UUID serialUnitId);
}
