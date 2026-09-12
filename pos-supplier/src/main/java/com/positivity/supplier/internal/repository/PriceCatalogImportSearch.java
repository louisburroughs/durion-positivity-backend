package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the PRICAT import-run search (#1637 decisions 4/6), built as a {@link
 * Specification} so that an absent filter emits no SQL at all.
 *
 * <h2>Why not {@code (:param IS NULL OR column = :param)}</h2>
 *
 * That is the obvious way to write an all-optional filter as one JPQL string, and it is how this
 * search was written until the H2 slices of this module moved onto PostgreSQL. It works on H2 and
 * fails on PostgreSQL for <em>every</em> call: a bare {@code ? IS NULL} gives PostgreSQL nothing to
 * infer the placeholder's type from, so the statement is rejected at parse time — before any value
 * is bound, which is why supplying the filter does not help either — with {@code could not
 * determine data type of parameter $n}. Every request to the endpoint was a 500 while the
 * H2-backed test of the same query stayed green. This is the same defect as issue #1891, which
 * {@link TransmissionLedgerSearch} fixed for the transmission ledger; the two PRICAT searches were
 * written in the same shape and carried the same bug.
 *
 * <p>Building the predicate list instead removes the failure by construction rather than by
 * casting each placeholder: a null filter contributes no predicate, so there is no untyped
 * placeholder for any dialect to reject, and no way to reintroduce one by adding a filter here
 * later. It also keeps the page query and its count in step for free — Spring Data derives the
 * count from this same specification, so the two cannot drift apart.
 *
 * <h2>The window is on {@code fetchedAt}</h2>
 *
 * When the vendor was called — the axis an operator reads a feed's history by — half-open
 * ({@code from} inclusive, {@code to} exclusive) so adjacent windows tile without listing a
 * boundary run twice. {@code bindingId} narrows a profile's history to one feed; pre-V19 rows
 * carry a null binding and therefore never match a binding filter, which is the documented
 * forward-only semantics.
 */
final class PriceCatalogImportSearch {

    private static final String VENDOR_PROFILE_ID = "vendorProfileId";
    private static final String BINDING_ID = "bindingId";
    private static final String STATUS = "status";
    private static final String FETCHED_AT = "fetchedAt";

    private PriceCatalogImportSearch() {}

    /**
     * The import-run filter. The profile scope is mandatory; every other clause is optional and a
     * null argument switches its predicate off rather than matching nothing.
     *
     * @param vendorProfileId the profile whose history is being read
     * @param bindingId only runs of this feed, or null for every feed of the profile
     * @param status only runs in this state, or null for every state
     * @param fetchedFrom inclusive lower bound on {@code fetchedAt}, or null
     * @param fetchedTo exclusive upper bound on {@code fetchedAt}, or null
     * @return the specification matching the profile and those filters that were supplied
     */
    @NonNull
    static Specification<PriceCatalogImportEntity> matching(
            @NonNull UUID vendorProfileId,
            @Nullable UUID bindingId,
            @Nullable PriceCatalogImportStatus status,
            @Nullable Instant fetchedFrom,
            @Nullable Instant fetchedTo) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(5);
            predicates.add(builder.equal(root.get(VENDOR_PROFILE_ID), vendorProfileId));
            if (bindingId != null) {
                predicates.add(builder.equal(root.get(BINDING_ID), bindingId));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get(STATUS), status));
            }
            if (fetchedFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get(FETCHED_AT), fetchedFrom));
            }
            if (fetchedTo != null) {
                predicates.add(builder.lessThan(root.get(FETCHED_AT), fetchedTo));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
