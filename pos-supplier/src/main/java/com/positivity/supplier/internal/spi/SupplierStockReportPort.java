package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockSnapshot;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: country-level vendor stock snapshot
 * ({@code STOCK_REPORT}; EDIWheel Stock Report B2.1).
 *
 * <p>Governing ADRs: ADR-0049 §3 (feeds {@code supplier.stockreport.updated} for pos-inventory),
 * ADR-0050 §3.
 *
 * <p>Distinct from {@link SupplierStockInquiryPort} in kind, not just in scope: an inquiry is a live
 * question asked while a customer waits, a report is a bulk snapshot fetched on a schedule. They
 * fail differently and are allowed to.
 */
public interface SupplierStockReportPort {

    /** Fetches the vendor's current stock snapshot, as stated. */
    @NonNull
    SupplierStockSnapshot fetchStockSnapshot(@NonNull SupplierRef supplierRef);
}
