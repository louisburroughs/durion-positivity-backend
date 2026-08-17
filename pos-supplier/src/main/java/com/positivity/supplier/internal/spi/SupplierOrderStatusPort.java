package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: order status tracking
 * ({@code ORDER_STATUS}; EDIWheel Order Status A2.5/C1.0/C1.1).
 *
 * <p>Governing ADRs: ADR-0049 §3, ADR-0052 §3 (order-status answers are how an ambiguous
 * transmission is reconciled — the vendor's own record is the tie-breaker).
 *
 * <p>Both references are accepted because either may be the one the vendor knows this order by: our
 * document id if it echoed it, its own order number if it assigned one. Asking with only one would
 * mean an order transmitted but not confirmed could never be looked up, which is precisely the case
 * this capability exists to resolve.
 */
public interface SupplierOrderStatusPort {

    /**
     * Asks the vendor what became of one order.
     *
     * @param supplierRef         the vendor profile alias to ask
     * @param documentId          the document id minted for the transmission, where known
     * @param supplierOrderNumber the vendor's own order number, where it gave one
     */
    @NonNull
    SupplierOrderStatusResult queryOrderStatus(
            @NonNull SupplierRef supplierRef,
            @org.jspecify.annotations.Nullable String documentId,
            @org.jspecify.annotations.Nullable String supplierOrderNumber);
}
