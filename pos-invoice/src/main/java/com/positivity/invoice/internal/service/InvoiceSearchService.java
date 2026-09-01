package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.InvoiceLineSearchResult;
import com.positivity.invoice.internal.dto.InvoiceSearchFilters;
import com.positivity.invoice.internal.dto.InvoiceSearchResult;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Free-text invoice search matching the invoice number, the customer name, or the
 * workorder number, combinable with structured filters (status, issued-date window,
 * customer id — #1599, E11).
 */
public interface InvoiceSearchService {

    /**
     * Search invoices by query string matching the invoice number directly, the customer
     * name (resolved to party ids via CRM), or the workorder number (resolved to workorder
     * ids via the workorder service), ANDed against {@code filters}. Result rows are
     * enriched with the resolved customer display name and human workorder number.
     *
     * <p>A blank {@code q} with {@link InvoiceSearchFilters#isEmpty() empty filters} degenerates
     * to an empty page rather than listing all invoices (unchanged from the pre-#1599 contract);
     * a blank {@code q} with at least one structured filter set performs a filtered listing
     * instead of short-circuiting.
     *
     * @param q        free-text query (invoice number, customer name, or workorder number)
     * @param filters  structured filters, independently optional and combinable with {@code q}
     *                 and with each other; use {@link InvoiceSearchFilters#NONE} for none
     * @param pageable pagination and sorting configuration
     * @return page of invoice search results
     */
    @NonNull
    Page<InvoiceSearchResult> search(
            @NonNull String q, @NonNull InvoiceSearchFilters filters, @NonNull Pageable pageable);

    /**
     * Invoice line items belonging to a customer party, flattened with their owning
     * invoice's identifying fields and ordered by invoice creation time descending, optionally
     * narrowed by a SKU/description term and bounded server-side. Built for sibling services
     * (warranty claims) correlating a claimed part/service to the original sale line.
     *
     * @param partyId the customer party id
     * @param q       optional case-insensitive term matched against the line description
     * @return matching invoice line items (empty when the party has no invoices)
     */
    @NonNull
    List<InvoiceLineSearchResult> searchLinesByParty(@NonNull UUID partyId, @Nullable String q);
}
