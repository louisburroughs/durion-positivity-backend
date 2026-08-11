package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierExchange;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: order-status query at the vendor
 * ({@code ORDER_STATUS}; EDIWheel Order Status A2.5/C1.0/C1.1).
 *
 * <p>Governing ADRs: ADR-0049 §1, ADR-0052 §3 — this port is the status-first reconciliation
 * primitive for ambiguous order-create outcomes: the vendor's answer (knows the document /
 * does not know it) decides between applying the recorded state and {@code MANUAL_REVIEW}.
 * Blind re-send never exists.
 */
public interface SupplierOrderStatusPort {

    /**
     * Queries the vendor's state of an order by its wire document id
     * ({@code DocumentID}/{@code CustomerReference}, derived from the transmission intent per
     * ADR-0052 §1).
     */
    @NonNull SupplierExchange<SupplierOrderStatusResult> queryOrderStatus(
            @NonNull SupplierRef supplierRef, @NonNull PartyContext partyContext, @NonNull String documentId);
}
