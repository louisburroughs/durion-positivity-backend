package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierExchange;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockSnapshot;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: batch stock snapshot fetch at country level
 * ({@code STOCK_REPORT}; EDIWheel Stock Report B2.1/C1.0).
 *
 * <p>Governing ADRs: ADR-0049 §3 (feeds the {@code supplier.stockreport.updated} fact consumed by
 * pos-inventory), ADR-0052 §5 (batch reads are idempotent by checkpointed window and may retry
 * freely).
 *
 * <p>The snapshot has its own model, {@link SupplierStockSnapshot}, since CAP-322: a report states
 * what a market holds, an inquiry answers what a named consignee can get, and the two stopped
 * being the same shape as soon as the report gained scope and vendor as-of semantics.
 */
public interface SupplierStockReportPort {

    /** Fetches the vendor's current stock snapshot for the profile's market. */
    @NonNull
    SupplierExchange<SupplierStockSnapshot> fetchStockSnapshot(
            @NonNull SupplierRef supplierRef, @NonNull PartyContext partyContext);
}
