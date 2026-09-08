package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountSchedule;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link CycleCountSchedule} rows (odoo-parity I1, issue #1031). */
public interface CycleCountScheduleRepository extends JpaRepository<CycleCountSchedule, UUID> {

    /** Active schedules whose next due date has arrived, oldest due first. */
    List<CycleCountSchedule> findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(LocalDate date);

    /**
     * Optionally filtered schedule listing. {@code dueOnOrBefore} non-null
     * restricts to ACTIVE schedules due on or before that date (the
     * "due for count" view); null filters are ignored.
     */
    @Query("""
            SELECT s FROM CycleCountSchedule s
            WHERE (:locationId IS NULL OR s.locationId = :locationId)
              AND (:active IS NULL OR s.active = :active)
              AND (:dueOnOrBefore IS NULL OR (s.active = true AND s.nextDueDate <= :dueOnOrBefore))
            """)
    Page<CycleCountSchedule> findByOptionalFilters(
            @Param("locationId") UUID locationId,
            @Param("active") Boolean active,
            @Param("dueOnOrBefore") LocalDate dueOnOrBefore,
            Pageable pageable);

    /**
     * {@link #findByOptionalFilters} narrowed to the caller's reach (ADR-0061 §3, #1872):
     * schedules at a reachable site, or at a storage location replicated under one. Never called
     * with an empty set — an empty reach is an empty page decided before the query.
     */
    @Query("""
            SELECT s FROM CycleCountSchedule s
            WHERE (s.locationId IN :locationIds
                   OR s.locationId IN (SELECT b.storageLocationId FROM ExtStorageLocationReplica b
                                       WHERE b.siteId IN :locationIds))
              AND (:active IS NULL OR s.active = :active)
              AND (:dueOnOrBefore IS NULL OR (s.active = true AND s.nextDueDate <= :dueOnOrBefore))
            """)
    Page<CycleCountSchedule> findByOptionalFiltersWithinLocations(
            @Param("locationIds") Collection<UUID> locationIds,
            @Param("active") Boolean active,
            @Param("dueOnOrBefore") LocalDate dueOnOrBefore,
            Pageable pageable);
}
