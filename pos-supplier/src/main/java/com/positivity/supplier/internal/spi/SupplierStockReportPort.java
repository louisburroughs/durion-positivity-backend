package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierExchange;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: batch stock snapshot fetch at country level
 * ({@code STOCK_REPORT}; EDIWheel Stock Report B2.1/C1.0).
 *
 * <p>Governing ADRs: ADR-0049 §3 (feeds the {@code supplier.stockreport.updated} fact
 * consumed by pos-inventory), ADR-0052 §5 (batch reads are idempotent by checkpointed window
 * and may retry freely).
 *
 * <p>The snapshot reuses {@link SupplierStockInquiryResult} as its shape (article identity,
 * quantity, as-of instant) until the stock-report capability (CAP-322) grows a dedicated
 * snapshot model.
 */
public interface SupplierStockReportPort {

    /** Fetches the vendor's current stock snapshot for the profile's market. */
    @NonNull SupplierExchange<SupplierStockInquiryResult> fetchStockSnapshot(
            @NonNull SupplierRef supplierRef, @NonNull PartyContext partyContext);
}
