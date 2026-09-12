package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountSchedule;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the cycle-count schedule listing (odoo-parity I1, issue #1031; location narrowing
 * per ADR-0061 §3, issue #1872), built as a {@link Specification} so that an absent filter emits no
 * SQL at all.
 *
 * <h2>Why not {@code (:param IS NULL OR column = :param)}</h2>
 *
 * That is the obvious way to write an all-optional filter as one JPQL string, and it is how this
 * listing was written until the module's slices moved onto PostgreSQL. A bare {@code ? IS NULL}
 * gives PostgreSQL nothing to infer the placeholder's type from, so the statement is rejected at
 * parse time — before any value is bound — with {@code could not determine data type of parameter
 * $n} (SQLSTATE 42P18). This is the defect of issue #1891 and PR #1961, which
 * {@code pos-supplier}'s {@code TransmissionLedgerSearch} and {@code PriceCatalogImportSearch}
 * fixed the same way.
 *
 * <p>Here it was the {@code dueOnOrBefore} clause that tripped, and only when the caller supplied a
 * date. The reason is the JDBC driver, not the value: pgjdbc binds a temporal value as a string
 * with the type OID left <em>unspecified</em>, so {@code $n IS NULL} over it is unresolvable, while
 * {@code setNull(Types.DATE)} for an omitted date does carry a concrete OID and happens to parse.
 * The {@code locationId} (UUID) and {@code active} (boolean) clauses beside it bind concrete OIDs
 * in both directions and did not trip — the same asymmetry PR #1961 saw, where PostgreSQL named
 * only the {@code Instant} parameter. So {@code GET /inventory/cycleCountSchedules?due=true} — the
 * "due for count" worklist — returned 500 on every call while the unfiltered listing beside it
 * worked and the H2-backed tests of both stayed green.
 *
 * <p>Building the predicate list instead removes the failure by construction rather than by casting
 * each placeholder: a null filter contributes no predicate, so there is no untyped placeholder for
 * any dialect to reject, and no way to reintroduce one by adding a filter here later. It also keeps
 * the page query and its count in step for free — Spring Data derives the count from this same
 * specification, so the two cannot drift apart.
 *
 * <h2>{@code dueOnOrBefore} implies active</h2>
 *
 * The due-for-count view is about work to do, and an inactive schedule is not work: supplying the
 * date restricts to {@code active = true} on top of whatever the {@code active} filter says,
 * exactly as the JPQL it replaces did.
 */
final class CycleCountScheduleSearch {

    private static final String LOCATION_ID = "locationId";
    private static final String ACTIVE = "active";
    private static final String NEXT_DUE_DATE = "nextDueDate";
    private static final String SITE_ID = "siteId";
    private static final String STORAGE_LOCATION_ID = "storageLocationId";

    private CycleCountScheduleSearch() {}

    /**
     * The schedule filter, unnarrowed. Every clause is optional: a null argument switches its
     * predicate off rather than matching nothing, so an unfiltered search pages every schedule.
     *
     * @param locationId only schedules at this location, or null for every location
     * @param active only schedules with this active flag, or null for both
     * @param dueOnOrBefore only active schedules due on or before this date, or null for any
     * @return the specification matching those of the arguments that were supplied
     */
    @NonNull
    static Specification<CycleCountSchedule> matching(
            @Nullable UUID locationId, @Nullable Boolean active, @Nullable LocalDate dueOnOrBefore) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(3);
            if (locationId != null) {
                predicates.add(builder.equal(root.get(LOCATION_ID), locationId));
            }
            addOptionalFilters(predicates, root, builder, active, dueOnOrBefore);
            // An empty conjunction is the unfiltered listing, which is a documented, supported call.
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * The schedule filter narrowed to the caller's reach (ADR-0061 §3, issue #1872): schedules at a
     * reachable site, or at a storage location replicated under one. Never called with an empty set
     * — an empty reach is an empty page decided before the query.
     *
     * @param locationIds the sites the caller can reach; never empty
     * @param active only schedules with this active flag, or null for both
     * @param dueOnOrBefore only active schedules due on or before this date, or null for any
     * @return the specification matching the reach and those filters that were supplied
     */
    @NonNull
    static Specification<CycleCountSchedule> withinLocations(
            @NonNull Collection<UUID> locationIds, @Nullable Boolean active, @Nullable LocalDate dueOnOrBefore) {
        return (root, query, builder) -> {
            Subquery<UUID> storageLocations = query.subquery(UUID.class);
            Root<ExtStorageLocationReplica> replica = storageLocations.from(ExtStorageLocationReplica.class);
            storageLocations
                    .select(replica.get(STORAGE_LOCATION_ID))
                    .where(replica.get(SITE_ID).in(locationIds));

            List<Predicate> predicates = new ArrayList<>(3);
            predicates.add(builder.or(
                    root.get(LOCATION_ID).in(locationIds), root.get(LOCATION_ID).in(storageLocations)));
            addOptionalFilters(predicates, root, builder, active, dueOnOrBefore);
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** The two filters both listings share, so the narrowed view cannot drift from the unnarrowed one. */
    private static void addOptionalFilters(
            List<Predicate> predicates,
            Root<CycleCountSchedule> root,
            jakarta.persistence.criteria.CriteriaBuilder builder,
            @Nullable Boolean active,
            @Nullable LocalDate dueOnOrBefore) {
        if (active != null) {
            predicates.add(builder.equal(root.get(ACTIVE), active));
        }
        if (dueOnOrBefore != null) {
            predicates.add(builder.and(
                    builder.isTrue(root.get(ACTIVE)),
                    builder.lessThanOrEqualTo(root.get(NEXT_DUE_DATE), dueOnOrBefore)));
        }
    }
}
