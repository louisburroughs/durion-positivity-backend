package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierExchange;
import com.positivity.supplier.internal.domain.model.SupplierInvoice;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: vendor AR transaction (invoice/credit note) fetch
 * ({@code INVOICE_FETCH}; EDIWheel Invoice B3.3).
 *
 * <p>Governing ADRs: ADR-0049 §3 (feeds the {@code supplier.invoice.received} fact consumed
 * by pos-accounting; AP voucher posting stays with pos-accounting), ADR-0052 §5 (batch reads
 * retry freely by checkpointed window; consumers deduplicate by natural vendor invoice
 * identity).
 */
public interface SupplierInvoicePort {

    /**
     * Fetches the vendor invoices issued in the inclusive document-date window
     * {@code [fromDate, toDate]}.
     */
    @NonNull
    SupplierExchange<List<SupplierInvoice>> fetchInvoices(
            @NonNull SupplierRef supplierRef,
            @NonNull PartyContext partyContext,
            @NonNull LocalDate fromDate,
            @NonNull LocalDate toDate);
}
