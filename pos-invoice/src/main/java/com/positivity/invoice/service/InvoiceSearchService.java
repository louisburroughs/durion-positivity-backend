package com.positivity.invoice.service;

import com.positivity.invoice.internal.dto.InvoiceLineSearchResult;
import com.positivity.invoice.internal.dto.InvoiceSearchResult;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Free-text invoice search matching the invoice number, the customer name, or the
 * workorder number.
 */
public interface InvoiceSearchService {

    /**
     * Search invoices by query string matching the invoice number directly, the customer
     * name (resolved to party ids via CRM), or the workorder number (resolved to workorder
     * ids via the workorder service). Result rows are enriched with the resolved customer
     * display name and human workorder number.
     *
     * @param q        free-text query (invoice number, customer name, or workorder number)
     * @param pageable pagination and sorting configuration
     * @return page of invoice search results
     */
    @NonNull
    Page<InvoiceSearchResult> search(@NonNull String q, @NonNull Pageable pageable);

    /**
     * All invoice line items belonging to a customer party, flattened with their owning
     * invoice's identifying fields and ordered by invoice creation time descending. Built for
     * sibling services (warranty claims) correlating a claimed part/service to the original
     * sale line.
     *
     * @param partyId the customer party id
     * @return matching invoice line items (empty when the party has no invoices)
     */
    @NonNull
    List<InvoiceLineSearchResult> searchLinesByParty(@NonNull UUID partyId);
}
