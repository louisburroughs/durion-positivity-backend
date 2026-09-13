package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.entity.VehicleRecord;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the vehicle fact replay (#1891), built as a {@link Specification} so that an absent
 * filter emits no SQL at all.
 *
 * <h2>Why not {@code (:param IS NULL OR column = :param)}</h2>
 *
 * That is the obvious way to write an all-optional filter as one JPQL string, and it is how this
 * replay was written until this fix. It works on H2 and fails on PostgreSQL for <em>every</em> call:
 * a bare {@code ? IS NULL} gives PostgreSQL nothing to infer the placeholder's type from, so the
 * statement is rejected at parse time — before any value is bound, which is why supplying the filter
 * did not help either — with {@code could not determine data type of parameter $4}. Every request to
 * the replay endpoint became a 500 while the H2-backed tests of the same query stayed green. This is
 * the defect of issue #1891, fixed there for {@code pos-supplier}'s transmission ledger and then for
 * {@code pos-catalog}'s three fact replays, which this one was modelled on.
 *
 * <p>The placeholder that actually trips is {@code updatedSince}, and only it: PostgreSQL infers a
 * UUID placeholder from the operand it is compared with — the {@code afterId} clause parsed fine —
 * but the pgjdbc driver deliberately sends timestamps with an unspecified type OID so the server can
 * coerce between {@code timestamp} and {@code timestamptz}, which leaves {@code ? IS NULL} on an
 * {@link Instant} with no type at all, in both the bound and the unbound case. Building the
 * predicate list removes the failure by construction rather than by casting each placeholder: a null
 * filter contributes no predicate, so there is no untyped placeholder for any dialect to reject, and
 * no way to reintroduce one by adding a filter here later.
 *
 * <h2>Both clauses are cursor semantics, not a search</h2>
 *
 * {@code afterId} is the replay cursor — strictly greater than, so a resumed page never re-emits the
 * row the previous page ended on — and {@code updatedSince} is inclusive, because a consumer asking
 * for everything changed at or after an instant means to include a row stamped exactly then.
 */
final class VehicleFactReplaySearch {

    private static final String VEHICLE_ID = "vehicleId";
    private static final String UPDATED_AT = "updatedAt";

    private VehicleFactReplaySearch() {}

    /**
     * The replay filter. Both clauses are optional: a null argument switches its predicate off
     * rather than matching nothing, so an unfiltered call replays the whole table from the
     * beginning.
     *
     * @param afterId resume strictly after this vehicle id, or null to start at the beginning
     * @param updatedSince only vehicles changed at or after this instant, or null for every vehicle
     * @return the specification matching those of the arguments that were supplied
     */
    @NonNull
    static Specification<VehicleRecord> matching(@Nullable UUID afterId, @Nullable Instant updatedSince) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(2);
            if (afterId != null) {
                predicates.add(builder.greaterThan(root.get(VEHICLE_ID), afterId));
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
