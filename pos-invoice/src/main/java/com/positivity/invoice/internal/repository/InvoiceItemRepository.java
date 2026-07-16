package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.InvoiceItem;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {

    @NonNull
    List<InvoiceItem> findByInvoice_Id(@NonNull UUID invoiceId);

    /**
     * Invoice line items belonging to a customer party, newest invoice first, optionally
     * narrowed by a description term (SKU/product text; LIKE-escaped by the caller) and bounded
     * by the supplied page request so a party with years of history cannot materialize an
     * unbounded result. The owning invoice is join-fetched (to-one, so pagination stays in SQL)
     * because the mapping reads its number/status/createdAt outside a lazy-loading session.
     *
     * @param partyId the customer party id (invoices store it as a string column)
     * @param q       optional case-insensitive description filter, pre-escaped for LIKE
     * @return matching line items ordered by owning-invoice creation time descending
     */
    @Query("SELECT ii FROM InvoiceItem ii JOIN FETCH ii.invoice i WHERE i.partyId = :partyId "
            + "AND (:q IS NULL OR LOWER(ii.description) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\') "
            + "ORDER BY i.createdAt DESC, ii.id ASC")
    @NonNull
    List<InvoiceItem> findByInvoicePartyId(
            @Param("partyId") @NonNull String partyId, @Param("q") @Nullable String q, @NonNull Pageable pageable);
}
