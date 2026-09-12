package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the PRICAT quarantine worklist search (#1637 decision 6), built as a {@link
 * Specification} so that an absent filter emits no SQL at all — see {@link PriceCatalogImportSearch}
 * for why the {@code (:param IS NULL OR …)} JPQL this replaced returned 500 from PostgreSQL for
 * every call while passing on H2 (the defect of issue #1891).
 *
 * <h2>{@code resolved} is a switch, not a filter</h2>
 *
 * It is a mandatory boolean rather than a nullable one: the worklist's default has always been the
 * open quarantine ({@code resolvedAt IS NULL}), and the service maps an omitted request parameter
 * to {@code false} so that default survives the optional filters beside it.
 *
 * <h2>The window is on {@code fetchedAt}</h2>
 *
 * Half-open ({@code from} inclusive, {@code to} exclusive) so adjacent windows tile without listing
 * a boundary line twice.
 */
final class PriceCatalogQuarantineSearch {

    /**
     * The escape character of the free-text {@code LIKE}, matching the pattern the service builds:
     * an operator pasting an article code containing {@code _} is quoting a literal reference, not
     * writing a wildcard.
     */
    private static final char LIKE_ESCAPE = '!';

    private static final String VENDOR_PROFILE_ID = "vendorProfileId";
    private static final String RESOLVED_AT = "resolvedAt";
    private static final String REASON = "reason";
    private static final String ARTICLE_EAN = "articleEan";
    private static final String SUPPLIER_ARTICLE_CODE = "supplierArticleCode";
    private static final String X_REFERENCE_CODE = "xReferenceCode";
    private static final String FETCHED_AT = "fetchedAt";

    private PriceCatalogQuarantineSearch() {}

    /**
     * The quarantine filter. The profile scope and the resolved toggle are mandatory; every other
     * clause is optional and a null argument switches its predicate off rather than matching
     * nothing.
     *
     * @param vendorProfileId the profile whose quarantine is being worked
     * @param resolved true for the closed lines an auditor reads, false for the open worklist
     * @param reason only lines quarantined for this reason, or null for every reason
     * @param searchPattern a pre-lowercased, pre-escaped {@code LIKE} pattern (escape character
     *     {@code !}) matched against the three identifiers the vendor line carried — EAN, the
     *     vendor's own article code, and the cross-reference code — or null to search none
     * @param fetchedFrom inclusive lower bound on {@code fetchedAt}, or null
     * @param fetchedTo exclusive upper bound on {@code fetchedAt}, or null
     * @return the specification matching the profile, the toggle and the filters that were supplied
     */
    @NonNull
    static Specification<PriceCatalogUnmatchedLineEntity> matching(
            @NonNull UUID vendorProfileId,
            boolean resolved,
            @Nullable UnmatchedLineReason reason,
            @Nullable String searchPattern,
            @Nullable Instant fetchedFrom,
            @Nullable Instant fetchedTo) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(6);
            predicates.add(builder.equal(root.get(VENDOR_PROFILE_ID), vendorProfileId));
            predicates.add(resolved ? builder.isNotNull(root.get(RESOLVED_AT)) : builder.isNull(root.get(RESOLVED_AT)));
            if (reason != null) {
                predicates.add(builder.equal(root.get(REASON), reason));
            }
            if (searchPattern != null) {
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get(ARTICLE_EAN)), searchPattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.get(SUPPLIER_ARTICLE_CODE)), searchPattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.get(X_REFERENCE_CODE)), searchPattern, LIKE_ESCAPE)));
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
