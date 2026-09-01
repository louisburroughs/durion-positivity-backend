package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
    Optional<Invoice> findByOrderId(@NonNull UUID orderId);

    @NonNull
    List<Invoice> findByStatus(@NonNull InvoiceStatus status);

    @NonNull
    Optional<Invoice> findByInvoiceNumber(@NonNull String invoiceNumber);

    /**
     * Free-text invoice search matching the invoice number (substring, case-insensitive),
     * the customer (party) ids resolved from a name query, or the workorder ids resolved
     * from a workorder-number query, ANDed against the structured filters added by #1599
     * (E11): exact {@code status}, a {@code finalizedAt} window ({@code issuedFrom}/
     * {@code issuedTo}), and exact {@code customerId} (party id). Every structured filter is
     * independently optional and combinable with the free-text leg and with each other.
     *
     * <p>JPQL {@code IN} requires non-empty collections; callers pass a non-matching
     * sentinel when a leg yields no ids. The free-text leg itself is skipped (never forced
     * to match) when {@code q} is the empty string — the {@code :q = ''} disjunct makes the
     * whole free-text group vacuously true so a caller can filter by structured fields alone
     * without supplying a query term.
     *
     * @param q            free-text query matched against the invoice number; empty string
     *                     disables the free-text leg entirely (structured filters only)
     * @param customerIds  party ids resolved from the customer-name leg
     * @param workorderIds workorder ids resolved from the workorder-number leg
     * @param status       exact status match; null disables the filter
     * @param issuedFrom   {@code finalizedAt} lower bound (inclusive); null disables the filter
     * @param issuedTo     {@code finalizedAt} upper bound (inclusive); null disables the filter
     * @param customerId   exact party id match; null disables the filter
     * @param pageable     pagination and sorting configuration
     * @return page of matching invoices
     */
    @Query("""
            SELECT i FROM Invoice i
            WHERE ((:q <> '' AND (
                       LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'
                       OR i.partyId IN :customerIds
                       OR i.workorderId IN :workorderIds))
                   OR :q = '')
              AND (:status IS NULL OR i.status = :status)
              AND (:issuedFrom IS NULL OR i.finalizedAt >= :issuedFrom)
              AND (:issuedTo IS NULL OR i.finalizedAt <= :issuedTo)
              AND (:customerId IS NULL OR i.partyId = :customerId)
            """)
    @NonNull
    Page<Invoice> searchByQuery(
            @Param("q") @NonNull String q,
            @Param("customerIds") @NonNull Collection<String> customerIds,
            @Param("workorderIds") @NonNull Collection<UUID> workorderIds,
            @Param("status") @Nullable InvoiceStatus status,
            @Param("issuedFrom") @Nullable Instant issuedFrom,
            @Param("issuedTo") @Nullable Instant issuedTo,
            @Param("customerId") @Nullable String customerId,
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

    /**
     * Revenue-by-customer analytics rows (#1589, E1): per-customer aggregate over
     * revenue-recognized invoices ({@code status IN :revenueStatuses} — DRAFT has not been
     * billed and CANCELLED/ERROR never will be) created in {@code [start, end]}, ordered by
     * revenue descending so the caller can take the top N by simply bounding {@code pageable}
     * rather than paging. Callers request one row more than their public {@code limit} so the
     * service layer can detect truncation without a second COUNT query.
     *
     * @param start           window start (inclusive), UTC instant
     * @param end             window end (inclusive), UTC instant
     * @param revenueStatuses invoice statuses counted as recognized revenue
     * @param pageable        row bound (size = limit + 1); sort is ignored, the query orders itself
     * @return one row per customer with revenue in the window, revenue descending
     */
    @Query("""
            SELECT i.partyId AS customerId, SUM(i.total) AS revenue, COUNT(i) AS invoiceCount,
                   MAX(i.createdAt) AS lastInvoiceDate
            FROM Invoice i
            WHERE i.partyId IS NOT NULL
              AND i.status IN :revenueStatuses
              AND i.createdAt >= :start AND i.createdAt <= :end
            GROUP BY i.partyId
            ORDER BY SUM(i.total) DESC
            """)
    @NonNull
    List<RevenueByCustomerProjection> revenueByCustomer(
            @Param("start") @NonNull Instant start,
            @Param("end") @NonNull Instant end,
            @Param("revenueStatuses") @NonNull Collection<InvoiceStatus> revenueStatuses,
            @NonNull Pageable pageable);

    /** Projection for {@link #revenueByCustomer}. */
    interface RevenueByCustomerProjection {
        String getCustomerId();

        BigDecimal getRevenue();

        long getInvoiceCount();

        Instant getLastInvoiceDate();
    }

    /**
     * Raw (invoice-creation, workorder-creation) instant pairs feeding the invoicing-lag report
     * (#1592, E4), bounded to invoices created in {@code [start, end]} that carry a
     * {@code workorderId} matching a known {@code ext_workorder} replica row. The LEFT JOIN is
     * deliberate — mirrors {@code AsnLineRepository#sumUnreceivedRemainderForSku} — so an
     * invoice whose replica has not caught up still comes back with a null
     * {@code workorderCreatedAt}, forcing the caller to exclude it explicitly rather than the
     * row silently vanishing from the result set. {@code workorderCreatedAt} may be null even
     * with a matched replica row: the fact carrying it may not have arrived yet, or the row was
     * written before #1592. Callers MUST exclude a null {@code workorderCreatedAt} from the
     * average rather than treat it as zero lag.
     *
     * @param start window start (inclusive), UTC instant
     * @param end   window end (inclusive), UTC instant
     * @return one row per matching invoice; {@code workorderCreatedAt} may be null
     */
    @Query("""
            SELECT i.createdAt AS invoiceCreatedAt, w.workorderCreatedAt AS workorderCreatedAt
            FROM Invoice i
            LEFT JOIN ExtWorkorderReplica w ON w.workorderId = i.workorderId
            WHERE i.workorderId IS NOT NULL
              AND i.createdAt >= :start AND i.createdAt <= :end
            """)
    @NonNull
    List<InvoicingLagPairProjection> invoicingLagPairs(
            @Param("start") @NonNull Instant start, @Param("end") @NonNull Instant end);

    /** Projection for {@link #invoicingLagPairs}. */
    interface InvoicingLagPairProjection {
        Instant getInvoiceCreatedAt();

        @Nullable
        Instant getWorkorderCreatedAt();
    }
}
