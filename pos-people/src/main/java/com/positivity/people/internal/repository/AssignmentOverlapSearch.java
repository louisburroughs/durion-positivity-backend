package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The overlap check a staffing assignment is admitted by, built as a {@link Specification} so that
 * an absent filter emits no SQL at all.
 *
 * <h2>Why not {@code (:effectiveTo IS NULL OR …)}</h2>
 *
 * That is the obvious way to write an optional bound as one JPQL string, and it is how both overlap
 * checks were written until this fix. It works on H2 and fails on PostgreSQL: a bare {@code ? IS
 * NULL} gives the server nothing to infer the placeholder's type from, so the statement is rejected
 * at parse time with {@code could not determine data type of parameter $n} (issue #1891).
 *
 * <h2>This one failed only half the time, which is worse</h2>
 *
 * The untyped placeholder was the {@code IS NULL} test on {@code effectiveTo}. Unlike an {@link
 * java.time.Instant} filter, a {@link LocalDate} <em>is</em> typed by the pgjdbc driver when it binds
 * a null — {@code setNull(Types.DATE)} carries the {@code date} type OID — but not when it binds a
 * value, because the driver sends dates and timestamps with an unspecified OID so the server can
 * coerce them. So the check parsed for an open-ended assignment and was a 500 for a closed one:
 * creating or updating an assignment with an {@code effectiveTo} failed every time, and the
 * identical call without one succeeded. A defect that tracks the shape of the request rather than
 * the endpoint is exactly the kind a green H2 test suite hides longest — the same asymmetry found on
 * pos-catalog's MSRP overlap query.
 *
 * <p>Building the predicate list removes the failure by construction rather than by casting the
 * placeholder: an absent bound contributes no predicate, so there is no untyped placeholder for any
 * dialect to reject, and no way to reintroduce one by adding a date filter here later.
 *
 * <h2>An open-ended assignment overlaps everything after it starts</h2>
 *
 * An assignment with no {@code effectiveTo} runs forever, so the lower-bound clause admits a null
 * end date alongside the comparison. Both bounds are inclusive: two assignments that merely touch on
 * a day do overlap on that day.
 */
final class AssignmentOverlapSearch {

    private static final String ID = "id";
    private static final String EMPLOYEE = "employee";
    private static final String PERSON_ID = "personId";
    private static final String LOCATION_ID = "locationId";
    private static final String ROLE = "role";
    private static final String STATUS = "status";
    private static final String EFFECTIVE_FROM = "effectiveFrom";
    private static final String EFFECTIVE_TO = "effectiveTo";

    private AssignmentOverlapSearch() {}

    /**
     * The active assignments of one person, location and role whose effective window overlaps the
     * candidate's.
     *
     * @param personId the person being assigned
     * @param locationId the location being assigned to
     * @param role the role being assigned; one person may hold two roles at one location at once
     * @param effectiveFrom the candidate's inclusive start; required, as an assignment always has one
     * @param effectiveTo the candidate's inclusive end, or null for an open-ended candidate — which
     *     has no upper bound, so the upper-bound predicate is switched off rather than matching
     *     nothing
     * @param excludeAssignmentId the assignment being updated, excluded so it cannot overlap itself,
     *     or null when the candidate is new
     * @return the specification matching the overlapping assignments
     */
    @NonNull
    static Specification<EmployeeLocationAssignment> matching(
            @NonNull UUID personId,
            @NonNull UUID locationId,
            @NonNull String role,
            @NonNull LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo,
            @Nullable UUID excludeAssignmentId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(6);
            predicates.add(builder.equal(root.get(EMPLOYEE).get(PERSON_ID), personId));
            predicates.add(builder.equal(root.get(LOCATION_ID), locationId));
            predicates.add(builder.equal(root.get(ROLE), role));
            predicates.add(builder.equal(root.get(STATUS), AssignmentStatus.ACTIVE));
            predicates.add(builder.or(
                    builder.isNull(root.get(EFFECTIVE_TO)),
                    builder.greaterThanOrEqualTo(root.get(EFFECTIVE_TO), effectiveFrom)));
            if (effectiveTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get(EFFECTIVE_FROM), effectiveTo));
            }
            if (excludeAssignmentId != null) {
                predicates.add(builder.notEqual(root.get(ID), excludeAssignmentId));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
