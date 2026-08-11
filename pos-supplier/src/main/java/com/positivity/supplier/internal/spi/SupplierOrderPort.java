package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierExchange;
import com.positivity.supplier.internal.domain.model.SupplierOrderResult;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: real-time purchase-order creation at the vendor
 * ({@code ORDER_CREATE}; EDIWheel Order Creation A2.5/C1.0).
 *
 * <p>Governing ADRs: ADR-0049 §1 (canonical model ownership), ADR-0052 (outbound
 * idempotency — the port performs exactly one dispatch of an already-minted transmission
 * intent whose {@code documentId} derives from the intent; retry, the attempt-state machine,
 * and ambiguous-outcome reconciliation are the orchestrator's responsibility, never the
 * adapter's).
 */
public interface SupplierOrderPort {

    /**
     * Transmits one purchase-order transmission intent to the vendor and returns the vendor's
     * definitive decision. Ambiguous outcomes (timeout after send) surface as exceptions for
     * the orchestrator's reconciliation flow (ADR-0052 §3), never as a fabricated result.
     */
    @NonNull
    SupplierExchange<SupplierOrderResult> createOrder(
            @NonNull SupplierRef supplierRef, @NonNull PartyContext partyContext, @NonNull SupplierPurchaseOrder order);
}
