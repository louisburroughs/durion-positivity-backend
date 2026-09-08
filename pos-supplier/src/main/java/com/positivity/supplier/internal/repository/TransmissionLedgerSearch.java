package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the cross-purchase-order ledger search (issue #1638 decision 6), built as a
 * {@link Specification} so that an absent filter emits no SQL at all.
 *
 * <h2>Why not {@code (:param IS NULL OR column = :param)}</h2>
 *
 * That is the obvious way to write an all-optional filter as one JPQL string, and it is how this
 * search was written until issue #1891. It works on H2 and fails on PostgreSQL for <em>every</em>
 * call: a bare {@code ? IS NULL} gives PostgreSQL nothing to infer the placeholder's type from, so
 * the statement is rejected at parse time — before any value is bound, which is why supplying the
 * filter did not help either — with {@code could not determine data type of parameter $n}. Every
 * request to the endpoint became a 500, including the {@code MANUAL_REVIEW} queue the operator
 * worklist is built on, while an H2-backed test of the same query stayed green.
 *
 * <p>Building the predicate list instead removes the failure by construction rather than by
 * casting each placeholder: a null filter contributes no predicate, so there is no untyped
 * placeholder for any dialect to reject, and no way to reintroduce one by adding a filter here
 * later. It also keeps the page query and its count in step for free — Spring Data derives the
 * count from this same specification, so the two cannot drift apart.
 *
 * <h2>The window is on {@code createdAt}</h2>
 *
 * When the intent was minted — when the order entered the vendor queue — because that is the axis
 * an operator works a worklist by, and it is immutable: unlike {@code updatedAt} or
 * {@code lastStatusAt}, a row cannot move out of a window the operator has already searched.
 * Half-open ({@code from} inclusive, {@code to} exclusive) so adjacent windows tile without listing
 * a boundary intent twice.
 */
final class TransmissionLedgerSearch {

    /**
     * The escape character of the free-text {@code LIKE}, matching the pattern the service builds:
     * an operator pasting an order number containing {@code _} is quoting a literal reference, not
     * writing a wildcard.
     */
    private static final char LIKE_ESCAPE = '!';

    private static final String ATTEMPT_STATE = "attemptState";
    private static final String VENDOR_PROFILE_ID = "vendorProfileId";
    private static final String PURCHASE_ORDER_NUMBER = "purchaseOrderNumber";
    private static final String SUPPLIER_ORDER_NUMBER = "supplierOrderNumber";
    private static final String CREATED_AT = "createdAt";

    private TransmissionLedgerSearch() {}

    /**
     * The ledger filter. Every clause is optional: a null argument switches its predicate off
     * rather than matching nothing, so an unfiltered search pages the whole ledger.
     *
     * @param attemptState only intents currently in this state, or null for every state
     * @param vendorProfileId only intents to this vendor profile, or null for every vendor
     * @param searchPattern a pre-lowercased, pre-escaped {@code LIKE} pattern matched against the
     *     buyer's and the vendor's order numbers — the two references a human on either end of a
     *     phone call would quote — or null to search neither
     * @param createdFrom inclusive lower bound on the intent's {@code createdAt}, or null
     * @param createdTo exclusive upper bound on the intent's {@code createdAt}, or null
     * @return the specification matching those of the arguments that were supplied
     */
    @NonNull
    static Specification<SupplierTransmissionIntentEntity> matching(
            @Nullable TransmissionAttemptState attemptState,
            @Nullable UUID vendorProfileId,
            @Nullable String searchPattern,
            @Nullable Instant createdFrom,
            @Nullable Instant createdTo) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(5);
            if (attemptState != null) {
                predicates.add(builder.equal(root.get(ATTEMPT_STATE), attemptState));
            }
            if (vendorProfileId != null) {
                predicates.add(builder.equal(root.get(VENDOR_PROFILE_ID), vendorProfileId));
            }
            if (searchPattern != null) {
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get(PURCHASE_ORDER_NUMBER)), searchPattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.get(SUPPLIER_ORDER_NUMBER)), searchPattern, LIKE_ESCAPE)));
            }
            if (createdFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get(CREATED_AT), createdFrom));
            }
            if (createdTo != null) {
                predicates.add(builder.lessThan(root.get(CREATED_AT), createdTo));
            }
            // An empty conjunction is the unfiltered ledger, which is a documented, supported call.
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
