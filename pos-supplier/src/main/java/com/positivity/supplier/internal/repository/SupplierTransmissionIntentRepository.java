package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The transmission ledger and its dispatch queue (ADR-0052 §§1–2). */
public interface SupplierTransmissionIntentRepository extends JpaRepository<SupplierTransmissionIntentEntity, UUID> {

    /**
     * The intent holding a tuple's active claim, if any.
     *
     * <p>This is the duplicate-order lookup: a command whose tuple is already claimed is a repeat,
     * not a new order.
     */
    Optional<SupplierTransmissionIntentEntity> findByActiveIntentKey(String activeIntentKey);

    /** Lookup by the wire document id — how a vendor's answer finds its intent. */
    Optional<SupplierTransmissionIntentEntity> findByDocumentId(String documentId);

    /**
     * Dispatchable work, oldest first. UUIDv7 ids are time-ordered, so a vendor receives one
     * customer's orders in the order they were placed.
     */
    List<SupplierTransmissionIntentEntity> findTop50ByAttemptStateOrderByTransmissionIntentIdAsc(
            TransmissionAttemptState attemptState);

    /**
     * Intents left mid-flight by a crash (ADR-0052 §2).
     *
     * <p>Never re-dispatched: they go to status reconciliation, or straight to
     * {@code MANUAL_REVIEW} when the profile has no {@code ORDER_STATUS} binding.
     */
    List<SupplierTransmissionIntentEntity> findByAttemptState(TransmissionAttemptState attemptState);

    /**
     * Orders the status poller still has something to learn about, least-recently-asked first.
     *
     * <p>Ordered by when the vendor was last <em>asked</em> rather than when its answer last
     * changed: ordering by the latter would starve an order whose answer never changes, which is
     * precisely the order most likely to need a human eventually.
     *
     * <p>Written out as a query rather than derived from the method name for one word:
     * <strong>{@code NULLS FIRST}</strong>. A newly confirmed order has never been polled, so its
     * {@code lastPolledAt} is null — and PostgreSQL sorts nulls <em>last</em> for {@code ASC},
     * which would put every brand-new order at the back of the queue behind orders already being
     * tracked. That is exactly backwards: the order nobody has asked about yet is the one with the
     * most to learn. Null is not a missing timestamp to be defaulted away, it is "never asked",
     * and never-asked sorts first.
     */
    @Query("select i from SupplierTransmissionIntentEntity i where i.statusPollingActive = true"
            + " order by i.lastPolledAt asc nulls first")
    List<SupplierTransmissionIntentEntity> findDueForStatusPolling(Pageable pageable);

    /** The transmission history of one purchase order, newest intent first. */
    List<SupplierTransmissionIntentEntity> findByPurchaseOrderIdOrderByTransmissionIntentIdDesc(UUID purchaseOrderId);

    /**
     * The shared filter of the cross-purchase-order ledger search, factored out so the page query
     * and its count cannot drift apart. Every clause is optional: a null parameter switches its
     * predicate off rather than matching nothing.
     *
     * <p>The window binds against {@code createdAt} — when the intent was minted, i.e. when the
     * order entered the vendor queue — because that is the axis an operator works a worklist by,
     * and it is immutable: unlike {@code updatedAt} or {@code lastStatusAt}, a row cannot move out
     * of a window the operator already searched. Half-open ({@code from} inclusive, {@code to}
     * exclusive) so adjacent windows tile without listing a boundary intent twice.
     *
     * <p>{@code searchPattern} is a pre-lowercased, pre-escaped {@code LIKE} pattern (escape
     * character {@code !}) built by the service, matched against the buyer's and the vendor's order
     * numbers — the two references a human on either end of a phone call would quote.
     */
    String SEARCH_WHERE = " WHERE (:attemptState IS NULL OR i.attemptState = :attemptState)"
            + " AND (:vendorProfileId IS NULL OR i.vendorProfileId = :vendorProfileId)"
            + " AND (:searchPattern IS NULL OR LOWER(i.purchaseOrderNumber) LIKE :searchPattern ESCAPE '!'"
            + " OR LOWER(i.supplierOrderNumber) LIKE :searchPattern ESCAPE '!')"
            + " AND (:createdFrom IS NULL OR i.createdAt >= :createdFrom)"
            + " AND (:createdTo IS NULL OR i.createdAt < :createdTo)";

    /**
     * The operator's ledger search (issue #1638 decision 6): one query across purchase orders,
     * filterable to the states that need a human — above all {@code MANUAL_REVIEW}.
     *
     * <p>Newest first by {@code createdAt}, with the UUIDv7 intent id as the deterministic
     * tie-break for rows minted in the same instant.
     */
    @Query(
            value = "SELECT i FROM SupplierTransmissionIntentEntity i" + SEARCH_WHERE
                    + " ORDER BY i.createdAt DESC, i.transmissionIntentId DESC",
            countQuery = "SELECT COUNT(i) FROM SupplierTransmissionIntentEntity i" + SEARCH_WHERE)
    @NonNull
    Page<SupplierTransmissionIntentEntity> search(
            @Param("attemptState") @Nullable TransmissionAttemptState attemptState,
            @Param("vendorProfileId") @Nullable UUID vendorProfileId,
            @Param("searchPattern") @Nullable String searchPattern,
            @Param("createdFrom") @Nullable Instant createdFrom,
            @Param("createdTo") @Nullable Instant createdTo,
            @NonNull Pageable pageable);
}
