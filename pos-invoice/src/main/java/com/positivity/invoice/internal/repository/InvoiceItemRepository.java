package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.InvoiceItem;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID>, JpaSpecificationExecutor<InvoiceItem> {

    @NonNull
    List<InvoiceItem> findByInvoice_Id(@NonNull UUID invoiceId);

    /**
     * Invoice line items belonging to a customer party, newest invoice first, optionally
     * narrowed by a description term (SKU/product text; LIKE-escaped by the caller) and bounded
     * by the supplied page request so a party with years of history cannot materialize an
     * unbounded result. The owning invoice is join-fetched (to-one, so pagination stays in SQL)
     * because the mapping reads its number/status/createdAt outside a lazy-loading session.
     *
     * <p>The filter is an {@link InvoiceLineSearch} specification rather than a JPQL string with an
     * {@code (:q IS NULL OR …)} clause: see that class for why the string form returned 500 from
     * PostgreSQL on every unfiltered call — the endpoint's ordinary case — while passing on H2 and
     * whenever a term happened to be supplied (issue #1891).
     *
     * <p>A list rather than a {@code Page}: the caller wants one capped batch, and asking for a
     * {@code Page} would make Spring Data run a count query over the party's whole billing history
     * to populate a total nobody reads.
     *
     * @param partyId the customer party id (invoices store it as a string column)
     * @param q       optional case-insensitive description filter, pre-escaped for LIKE
     * @param pageable supplies the result bound only; the order is fixed by the search
     * @return matching line items ordered by owning-invoice creation time descending
     */
    @NonNull
    default List<InvoiceItem> findByInvoicePartyId(
            @NonNull String partyId, @Nullable String q, @NonNull Pageable pageable) {
        return findBy(
                InvoiceLineSearch.matching(partyId, q),
                query -> (pageable.isUnpaged() ? query : query.limit(pageable.getPageSize())).all());
    }
}
