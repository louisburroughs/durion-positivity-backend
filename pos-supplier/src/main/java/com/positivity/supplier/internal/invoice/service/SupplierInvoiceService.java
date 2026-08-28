package com.positivity.supplier.internal.invoice.service;

import com.positivity.supplier.internal.invoice.service.model.SupplierInvoiceSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read surface over vendor invoices fetched through the supplier invoice capability
 * (ADR-0026 contract).
 *
 * <p>Fetched invoices flow to accounting as the {@code supplier.invoice.received} fact
 * (pos-accounting — ADR-0049 §3); AP voucher posting is the accounting consumer's concern.
 * This interface exposes the fetched-document ledger for the module's own frontend-facing
 * controllers.
 *
 * <p>Placeholder-level for the CAP-317 foundation slice; implementation arrives with CAP-321
 * (Invoice fetch B3.3, durion#376).
 */
public interface SupplierInvoiceService {

    /**
     * Returns summaries of vendor invoices received for a profile within a document-date range.
     *
     * @param vendorProfileId vendor profile identity (UUIDv7, ADR-0050 §1)
     * @param fromDate first invoice document date included
     * @param toDate last invoice document date included
     * @return matching invoice summaries, newest first; empty when none
     */
    @NonNull
    List<SupplierInvoiceSummary> findReceivedInvoices(
            @NonNull UUID vendorProfileId, @NonNull LocalDate fromDate, @NonNull LocalDate toDate);
}
