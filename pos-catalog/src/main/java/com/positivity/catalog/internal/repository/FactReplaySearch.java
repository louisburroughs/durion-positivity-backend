package com.positivity.catalog.internal.repository;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter shared by every current-state fact replay in this module — products (#1309), services
 * (#1306) and supplier article codes (#1347) — built as a {@link Specification} so that an absent
 * filter emits no SQL at all.
 *
 * <h2>Why not {@code (:param IS NULL OR column = :param)}</h2>
 *
 * That is the obvious way to write an all-optional filter as one JPQL string, and it is how all
 * three replays were written until this fix. It works on H2 and fails on PostgreSQL for
 * <em>every</em> call: a bare {@code ? IS NULL} gives PostgreSQL nothing to infer the placeholder's
 * type from, so the statement is rejected at parse time — before any value is bound, which is why
 * supplying the filter did not help either — with {@code could not determine data type of parameter
 * $n}. The three replay endpoints returned 500 on every request while the H2-backed tests of the
 * same queries stayed green. This is the defect of issue #1891, fixed there for {@code pos-supplier}'s
 * transmission ledger and then for its two PRICAT searches.
 *
 * <p>The placeholder that actually trips is {@code updatedSince}. PostgreSQL infers a UUID or a
 * string placeholder from the operand it is compared with, but the pgjdbc driver deliberately sends
 * timestamps with an unspecified type OID so the server can coerce between {@code timestamp} and
 * {@code timestamptz} — which leaves {@code ? IS NULL} on an {@link Instant} with no type at all, in
 * both the bound and the unbound case. Building the predicate list removes the failure by
 * construction rather than by casting each placeholder: a null filter contributes no predicate, so
 * there is no untyped placeholder for any dialect to reject, and no way to reintroduce one by adding
 * a filter here later.
 *
 * <h2>Both clauses are cursor semantics, not a search</h2>
 *
 * {@code afterId} is the replay cursor — strictly greater than, so a resumed page never re-emits the
 * row the previous page ended on — and {@code updatedSince} is inclusive, because a consumer asking
 * for everything changed at or after an instant means to include a row stamped exactly then.
 */
final class FactReplaySearch {

    private static final String ID = "id";
    private static final String UPDATED_AT = "updatedAt";

    private FactReplaySearch() {}

    /**
     * The replay filter, for any entity keyed by {@code id} and stamped with {@code updatedAt}.
     *
     * <p>Both clauses are optional: a null argument switches its predicate off rather than matching
     * nothing, so an unfiltered call replays the whole table from the beginning.
     *
     * @param <T> the entity being replayed
     * @param afterId resume strictly after this id, or null to start at the beginning
     * @param updatedSince only rows changed at or after this instant, or null for every row
     * @return the specification matching those of the arguments that were supplied
     */
    @NonNull
    static <T> Specification<T> matching(@Nullable UUID afterId, @Nullable Instant updatedSince) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(2);
            if (afterId != null) {
                predicates.add(builder.greaterThan(root.get(ID), afterId));
            }
            if (updatedSince != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get(UPDATED_AT), updatedSince));
            }
            // An empty conjunction is the whole table from the beginning, which is the documented
            // shape of the first page of an unfiltered replay.
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
