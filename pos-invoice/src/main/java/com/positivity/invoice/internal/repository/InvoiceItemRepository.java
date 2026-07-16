package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.InvoiceItem;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {

    @NonNull
    List<InvoiceItem> findByInvoice_Id(@NonNull UUID invoiceId);

    /**
     * All invoice line items belonging to a customer party, newest invoice first. The owning
     * invoice is join-fetched because the mapping reads its number/status/createdAt outside a
     * lazy-loading session.
     *
     * @param partyId the customer party id (invoices store it as a string column)
     * @return matching line items ordered by owning-invoice creation time descending
     */
    @Query("SELECT ii FROM InvoiceItem ii JOIN FETCH ii.invoice i WHERE i.partyId = :partyId "
            + "ORDER BY i.createdAt DESC, ii.id ASC")
    @NonNull
    List<InvoiceItem> findByInvoicePartyId(@Param("partyId") @NonNull String partyId);
}
