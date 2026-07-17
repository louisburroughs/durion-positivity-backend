package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @NonNull
    Optional<Invoice> findByWorkorderId(@NonNull UUID workorderId);

    @NonNull
    List<Invoice> findByStatus(@NonNull InvoiceStatus status);

    @NonNull
    Optional<Invoice> findByInvoiceNumber(@NonNull String invoiceNumber);

    /**
     * Free-text invoice search matching the invoice number (substring, case-insensitive),
     * the customer (party) ids resolved from a name query, or the workorder ids resolved
     * from a workorder-number query.
     *
     * <p>JPQL {@code IN} requires non-empty collections; callers pass a non-matching
     * sentinel when a leg yields no ids.
     *
     * @param q            free-text query matched against the invoice number
     * @param customerIds  party ids resolved from the customer-name leg
     * @param workorderIds workorder ids resolved from the workorder-number leg
     * @param pageable     pagination and sorting configuration
     * @return page of matching invoices
     */
    @Query("SELECT i FROM Invoice i WHERE LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\' "
            + "OR i.partyId IN :customerIds "
            + "OR i.workorderId IN :workorderIds")
    @NonNull
    Page<Invoice> searchByQuery(
            @Param("q") @NonNull String q,
            @Param("customerIds") @NonNull Collection<String> customerIds,
            @Param("workorderIds") @NonNull Collection<UUID> workorderIds,
            @NonNull Pageable pageable);

    /**
     * Backfills {@code invoices.customer_id} for pre-#920 invoices (NULL party) from the
     * event-fed {@code ext_workorder} replica (#921). Stores the canonical lowercase UUID
     * string so party-scoped lookups ({@code findByInvoicePartyId}) match; the
     * {@code LOWER(CAST(...))} form renders lowercase on both Postgres and H2. Idempotent —
     * a patched row no longer satisfies {@code customer_id IS NULL} — and cheap when there
     * is nothing to do.
     *
     * @return number of invoices patched in this run
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value = "UPDATE invoices inv SET customer_id = ("
                    + "SELECT LOWER(CAST(w.customer_id AS VARCHAR(64))) FROM ext_workorder w "
                    + "WHERE w.workorder_id = inv.workorder_id AND w.customer_id IS NOT NULL) "
                    + "WHERE inv.customer_id IS NULL "
                    + "AND EXISTS (SELECT 1 FROM ext_workorder w2 "
                    + "WHERE w2.workorder_id = inv.workorder_id AND w2.customer_id IS NOT NULL)",
            nativeQuery = true)
    int backfillPartyIdFromWorkorderReplica();
}
