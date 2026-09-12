package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.AbstractParty;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the party fact-replay cursor read (issue #1893), shared by both party tables and
 * built as a {@link Specification} so that an absent filter emits no SQL at all.
 *
 * <h2>Why not {@code (:param IS NULL OR column …)}</h2>
 *
 * That is the obvious way to write an all-optional filter as one JPQL string, and it is how both
 * {@code findForReplay} queries were written until this class existed. It works on H2 and fails on
 * PostgreSQL: a bare {@code ? IS NULL} gives PostgreSQL nothing to infer the placeholder's type
 * from, so the statement is rejected at parse time — before any value is bound, which is why
 * supplying the filter did not help either — with {@code could not determine data type of parameter
 * $n} (SQLSTATE 42P18).
 *
 * <p>Whether a given placeholder actually trips depends on what the driver can name for it. The
 * {@code afterId} comparison bound a UUID, which pgjdbc sends with its own type OID; the
 * {@code updatedSince} comparison bound an {@code Instant}, which it sends unspecified. So the
 * replay endpoint returned 500 on <em>every</em> call — the first page of a replay included, since
 * it supplies neither filter — while the H2-backed tests stayed green. The same failure was found
 * twice before this one, in {@code pos-supplier}'s transmission ledger (issue #1891) and in its
 * price-catalog searches (PR #1961).
 *
 * <p>Building the predicate list instead removes the failure by construction rather than by casting
 * each placeholder: a null filter contributes no predicate, so there is no untyped placeholder for
 * any dialect to reject, and no way to reintroduce one by adding a filter here later.
 */
final class PartyReplaySearch {

    private static final String PARTY_ID = "partyId";
    private static final String UPDATED_AT = "updatedAt";

    private PartyReplaySearch() {}

    /**
     * The replay filter. Both clauses are optional: a null argument switches its predicate off
     * rather than matching nothing, so a call with neither replays the whole customer base from the
     * beginning — which is exactly how a replay starts.
     *
     * @param afterId resume cursor; only parties whose id sorts strictly after it, or null to start
     *     at the beginning
     * @param updatedSince only parties whose {@code updatedAt} is at or after this instant, or null
     *     for every party regardless of when it last changed
     * @param <T> the concrete party table being replayed
     * @return the specification matching those of the arguments that were supplied
     */
    @NonNull
    static <T extends AbstractParty> Specification<T> matching(@Nullable UUID afterId, @Nullable Instant updatedSince) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(2);
            if (afterId != null) {
                predicates.add(builder.greaterThan(root.get(PARTY_ID), afterId));
            }
            if (updatedSince != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get(UPDATED_AT), updatedSince));
            }
            // An empty conjunction is the unfiltered replay, which is the documented starting call.
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
