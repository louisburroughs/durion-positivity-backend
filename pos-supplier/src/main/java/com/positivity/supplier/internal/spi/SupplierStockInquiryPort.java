package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierExchange;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: real-time stock availability inquiry at the vendor
 * ({@code STOCK_INQUIRY}; EDIWheel Stock Inquiry / UPX A2.5/C1.0).
 *
 * <p>Governing ADRs: ADR-0049 §4 (backs the platform's single synchronous cross-module
 * supplier read, {@code SupplierStockService}), ADR-0050 §3 (unbound capability surfaces as
 * the typed {@code CAPABILITY_NOT_CONFIGURED} status, never an error leak).
 */
public interface SupplierStockInquiryPort {

    /** Inquires availability (and optional quote) for the given articles. */
    @NonNull
    SupplierExchange<SupplierStockInquiryResult> inquireAvailability(
            @NonNull SupplierRef supplierRef,
            @NonNull PartyContext partyContext,
            @NonNull SupplierStockInquiry inquiry);
}
