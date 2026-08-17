package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: real-time stock availability inquiry at the vendor
 * ({@code STOCK_INQUIRY}; EDIWheel Stock Inquiry / UPX A2.5/C1.0).
 *
 * <p>Governing ADRs: ADR-0049 §4 (backs the platform's single synchronous cross-domain supplier
 * read, {@code SupplierStockService}), ADR-0050 §3 (unbound capabilities answer with the typed
 * {@code CAPABILITY_NOT_CONFIGURED} status, never an error leak).
 *
 * <h2>This one never throws</h2>
 *
 * Alone among the ports, every failure here is a status rather than an exception. A customer is
 * waiting on a screen, and a vendor being down must not take that screen with it — the honest answer
 * is "we could not find out", which is a value the caller renders, not an error it catches.
 */
public interface SupplierStockInquiryPort {

    /**
     * Asks the vendor what it has, for one receiving location.
     *
     * @param supplierRef        the vendor profile alias to ask
     * @param deliveryLocationId the receiving location the question is about; required, because
     *                           availability is consignee-specific
     * @param inquiry            the articles to ask about
     * @return the canonical answer; never null, and never an exception for a vendor-side failure
     */
    @NonNull
    SupplierStockInquiryResult inquireAvailability(
            @NonNull SupplierRef supplierRef, @NonNull UUID deliveryLocationId, @NonNull SupplierStockInquiry inquiry);
}
