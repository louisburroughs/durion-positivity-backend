package com.positivity.price.internal.repository;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The overlap guard shared by this module's two scheduled-price tables — the customer tier rule
 * (#51) and the location price override (#51) — built as a {@link Specification} so that an absent
 * filter emits no SQL at all.
 *
 * <h2>Why not {@code (:param IS NULL OR column = :param)}</h2>
 *
 * That is the obvious way to write an all-optional filter as one JPQL string, and it is how both
 * guards were written until this fix. It works on H2 and fails on PostgreSQL for <em>every</em>
 * call: a bare {@code ? IS NULL} gives PostgreSQL nothing to infer the placeholder's type from, so
 * the statement is rejected at parse time — before any value is bound, which is why supplying the
 * filter did not help either — with {@code could not determine data type of parameter $n}. This is
 * the defect of issue #1891, fixed there for {@code pos-supplier} and then for {@code pos-catalog}
 * and {@code pos-customer}.
 *
 * <p>The placeholder that actually trips is {@code effectiveTo}. PostgreSQL infers a UUID
 * placeholder from the operand it is compared with — which is why the {@code excludeId} clause
 * beside it parsed fine — but the pgjdbc driver deliberately sends timestamps with an unspecified
 * type OID so the server can coerce between {@code timestamp} and {@code timestamptz}, which leaves
 * {@code ? IS NULL} on an {@link Instant} with no type at all, in both the bound and the unbound
 * case. Building the predicate list removes the failure by construction rather than by casting each
 * placeholder: a null filter contributes no predicate, so there is no untyped placeholder for any
 * dialect to reject, and no way to reintroduce one by adding a filter here later.
 *
 * <h2>Half-open windows</h2>
 *
 * A scheduled price occupies {@code [effectiveFrom, effectiveTo)}, with a null {@code effectiveTo}
 * meaning "until further notice". Two such windows overlap when each starts before the other ends,
 * so a window that starts exactly where another ends is not an overlap and consecutive prices tile
 * without a conflict.
 */
final class EffectiveWindowOverlapSearch {

    private static final String ID = "id";
    private static final String PRODUCT_ID = "productId";
    private static final String EFFECTIVE_FROM = "effectiveFrom";
    private static final String EFFECTIVE_TO = "effectiveTo";

    private EffectiveWindowOverlapSearch() {}

    /**
     * Rows of one product-and-scope series whose effective window overlaps the candidate one.
     *
     * @param <T> the scheduled-price entity being guarded
     * @param productId the product the candidate window prices; always required
     * @param scopeAttribute the entity attribute naming the second key of the series — {@code
     *     customerTierId} for a tier rule, {@code locationId} for a location override
     * @param scopeId the value of that second key; always required
     * @param effectiveFrom the candidate window's inclusive start; always required
     * @param effectiveTo the candidate window's exclusive end, or null for "until further notice",
     *     in which case the candidate window has no upper bound to exclude anything by
     * @param excludeId the row being edited, excluded so that it does not overlap itself, or null
     *     when the candidate window belongs to a row that does not exist yet
     * @return the specification matching every conflicting row
     */
    @NonNull
    static <T> Specification<T> matching(
            @NonNull UUID productId,
            @NonNull String scopeAttribute,
            @NonNull UUID scopeId,
            @NonNull Instant effectiveFrom,
            @Nullable Instant effectiveTo,
            @Nullable UUID excludeId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(5);
            predicates.add(builder.equal(root.get(PRODUCT_ID), productId));
            predicates.add(builder.equal(root.get(scopeAttribute), scopeId));
            if (excludeId != null) {
                predicates.add(builder.notEqual(root.get(ID), excludeId));
            }
            if (effectiveTo != null) {
                predicates.add(builder.lessThan(root.get(EFFECTIVE_FROM), effectiveTo));
            }
            // A stored row with no end never stops applying, so it conflicts with any later window.
            predicates.add(builder.or(
                    builder.isNull(root.get(EFFECTIVE_TO)),
                    builder.greaterThan(root.get(EFFECTIVE_TO), effectiveFrom)));
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
