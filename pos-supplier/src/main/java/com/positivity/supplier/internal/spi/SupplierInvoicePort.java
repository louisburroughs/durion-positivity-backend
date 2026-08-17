package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.SupplierInvoice;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: vendor AR transaction (invoice/credit note) fetch
 * ({@code INVOICE_FETCH}; EDIWheel Invoice B3.3).
 *
 * <p>Governing ADRs: ADR-0049 §3 (feeds the {@code supplier.invoice.received} fact consumed by
 * pos-accounting; AP voucher posting stays with pos-accounting), ADR-0052 §5 (safe to retry freely
 * by checkpointed window; consumers deduplicate by natural vendor identity).
 *
 * <h2>This one always throws</h2>
 *
 * The opposite of the stock inquiry, and for the opposite reason. Nobody is waiting, and the caller
 * advances a checkpoint in the same transaction — so a failure reported as an empty window would
 * move the checkpoint past invoices that were never fetched. A missing invoice is not noticed until
 * a vendor asks why it has not been paid.
 */
public interface SupplierInvoicePort {

    /**
     * Fetches the vendor invoices issued in the inclusive document-date window
     * {@code [fromDate, toDate]}.
     *
     * <p>Returns what the vendor sent, transcribed. Storing them, deduplicating them and publishing
     * them are the caller's business; this is the vendor conversation and nothing else.
     *
     * @throws com.positivity.supplier.internal.exception.InvoiceFetchException when the vendor could
     *     not be reached, refused the window, or answered unreadably — never an empty list for any
     *     of those
     */
    @NonNull
    List<SupplierInvoice> fetchInvoices(
            @NonNull SupplierRef supplierRef, @NonNull LocalDate fromDate, @NonNull LocalDate toDate);
}
