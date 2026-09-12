package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountSchedule;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Repository for {@link CycleCountSchedule} rows (odoo-parity I1, issue #1031). */
public interface CycleCountScheduleRepository
        extends JpaRepository<CycleCountSchedule, UUID>, JpaSpecificationExecutor<CycleCountSchedule> {

    /** Active schedules whose next due date has arrived, oldest due first. */
    List<CycleCountSchedule> findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(LocalDate date);

    /**
     * The listing's documented order: newest first by {@code createdAt}, with the UUIDv7 schedule id
     * as the deterministic tie-break for rows created in the same instant. Imposed by the search
     * rather than taken from the caller, because it is part of the endpoint's contract.
     */
    Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("scheduleId"));

    /**
     * Optionally filtered schedule listing. {@code dueOnOrBefore} non-null restricts to ACTIVE
     * schedules due on or before that date (the "due for count" view); null filters are ignored.
     *
     * <p>The filter is a {@link CycleCountScheduleSearch} specification rather than a JPQL string of
     * {@code (:param IS NULL OR …)} clauses: see that class for why the string form made
     * {@code due=true} return 500 from PostgreSQL on every call while passing on H2 (the defect of
     * issue #1891). Spring Data derives the count query from the same specification, so page and
     * count cannot drift apart.
     *
     * @param locationId only schedules at this location, or null for every location
     * @param active only schedules with this active flag, or null for both
     * @param dueOnOrBefore only active schedules due on or before this date, or null for any
     * @param pageable the page to return, or {@code Pageable.unpaged()} for all matches; its sort is
     *     replaced by {@link #NEWEST_FIRST} either way
     * @return one page of matching schedules, newest first
     */
    @NonNull
    default Page<CycleCountSchedule> findByOptionalFilters(
            @Nullable UUID locationId,
            @Nullable Boolean active,
            @Nullable LocalDate dueOnOrBefore,
            @NonNull Pageable pageable) {
        return findAll(CycleCountScheduleSearch.matching(locationId, active, dueOnOrBefore), newestFirst(pageable));
    }

    /**
     * {@link #findByOptionalFilters} narrowed to the caller's reach (ADR-0061 §3, #1872): schedules
     * at a reachable site, or at a storage location replicated under one. Never called with an empty
     * set — an empty reach is an empty page decided before the query.
     *
     * @param locationIds the sites the caller can reach; never empty
     * @param active only schedules with this active flag, or null for both
     * @param dueOnOrBefore only active schedules due on or before this date, or null for any
     * @param pageable the page to return, or {@code Pageable.unpaged()} for all matches; its sort is
     *     replaced by {@link #NEWEST_FIRST} either way
     * @return one page of matching schedules within the reach, newest first
     */
    @NonNull
    default Page<CycleCountSchedule> findByOptionalFiltersWithinLocations(
            @NonNull Collection<UUID> locationIds,
            @Nullable Boolean active,
            @Nullable LocalDate dueOnOrBefore,
            @NonNull Pageable pageable) {
        return findAll(
                CycleCountScheduleSearch.withinLocations(locationIds, active, dueOnOrBefore), newestFirst(pageable));
    }

    /** The caller's page, re-sorted into the listing's contract order. */
    private static Pageable newestFirst(Pageable pageable) {
        return pageable.isUnpaged()
                ? Pageable.unpaged(NEWEST_FIRST)
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
    }
}
