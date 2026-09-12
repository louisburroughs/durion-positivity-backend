package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {

    Optional<SalesOrder> findByCreationIdempotencyKey(String creationIdempotencyKey);

    Optional<SalesOrder> findByCheckoutIdempotencyKey(String checkoutIdempotencyKey);

    Optional<SalesOrder> findByInvoiceId(UUID invoiceId);

    /**
     * The cart worklist search behind {@code GET /v1/orders/carts}. Every filter is optional: a
     * null argument switches its clause off rather than matching nothing.
     *
     * <p>Deliberately left as one JPQL string of {@code (:param IS NULL OR column = :param)}
     * clauses, which is the shape that returned 500 from PostgreSQL for every call in
     * {@code pos-price} and {@code pos-tax} (issue #1891). It is safe <em>here</em> because
     * PostgreSQL rejects that shape only when the placeholder's type cannot be inferred, and all
     * three of these are inferrable: two {@code String}s and an enum, each bound as varchar with a
     * concrete type OID for a value and for {@code setNull} alike.
     *
     * <p>That safety is a property of the parameter types, not of the query, so it does not survive
     * a new filter. Adding an optional {@code Instant} window over {@code createdAt} — the obvious
     * next thing a worklist wants — would break every call to this endpoint, supplied filter or
     * not, because pgjdbc sends temporal values with the type OID left unspecified. Build any such
     * filter as a {@code Specification} instead, the way
     * {@code com.positivity.price.internal.repository.EffectiveWindowOverlapSearch} does.
     * {@code SalesOrderRepositoryTest} issues this statement to a real PostgreSQL so that the
     * mistake fails the build rather than production.
     *
     * @param clerkId only carts opened by this clerk, or null for every clerk
     * @param terminalId only carts opened on this terminal, or null for every terminal
     * @param status only carts in this status, or null for every status
     * @param pageable the page to return; the order is always newest first
     * @return one page of matching orders, newest first
     */
    @Query("select o from SalesOrder o"
            + " where (:clerkId is null or o.clerkId = :clerkId)"
            + " and (:terminalId is null or o.terminalId = :terminalId)"
            + " and (:status is null or o.status = :status)"
            + " order by o.createdAt desc")
    Page<SalesOrder> search(
            @Param("clerkId") String clerkId,
            @Param("terminalId") String terminalId,
            @Param("status") SalesOrderStatus status,
            Pageable pageable);

    List<SalesOrder> findBySessionId(UUID sessionId);

    boolean existsBySessionIdAndStatus(UUID sessionId, SalesOrderStatus status);
}
