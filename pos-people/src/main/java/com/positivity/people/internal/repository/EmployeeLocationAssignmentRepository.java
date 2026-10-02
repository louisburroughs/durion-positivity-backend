package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.EmployeeStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeLocationAssignmentRepository
        extends JpaRepository<EmployeeLocationAssignment, UUID>, JpaSpecificationExecutor<EmployeeLocationAssignment> {

    List<EmployeeLocationAssignment> findByEmployee_PersonId(@NonNull UUID personId);

    Optional<EmployeeLocationAssignment> findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(
            @NonNull UUID personId, @NonNull AssignmentStatus status);

    @Query("""
            SELECT a FROM EmployeeLocationAssignment a
            WHERE a.status = 'ACTIVE'
              AND a.effectiveFrom <= :date
              AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date)
              AND (:locationId IS NULL OR a.locationId = :locationId)
            ORDER BY a.locationId, a.employee.personId, a.effectiveFrom DESC
            """)
    @NonNull
    List<EmployeeLocationAssignment> findActiveByDateAndOptionalLocation(
            @Param("date") @NonNull LocalDate date, @Param("locationId") UUID locationId);

    @Query("""
            SELECT a FROM EmployeeLocationAssignment a
            WHERE a.employee.personId = :personId
              AND a.status = 'ACTIVE'
              AND a.effectiveFrom <= :date
              AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date)
            ORDER BY a.isPrimary DESC, a.effectiveFrom DESC
            """)
    @NonNull
    List<EmployeeLocationAssignment> findActiveByPersonIdAndDate(
            @Param("personId") @NonNull UUID personId, @Param("date") @NonNull LocalDate date);

    /**
     * ACTIVE assignments an offboarding should already have ended, held by employees in one of the
     * given statuses (#2121): those whose {@code effectiveTo} is before {@code date} (a GRACE_PERIOD
     * that has run out), and those with no {@code effectiveTo} at all whose employee's status changed
     * before {@code settledBefore} and who has no offboarding retry still pending.
     *
     * <p>The open-ended branch is deliberately conservative, because it ends an assignment today
     * whatever the request asked for. {@code disableEmployee} writes its retry row in the
     * transaction that changes the status (#2360), so a disabled employee's assignments are held
     * back by that row until the after-commit handler or the retry worker has applied the request's
     * own policy and deleted it. A status moved into TERMINATED or DISABLED through
     * {@code updateEmployee} writes the same row, with the IMMEDIATE policy (#2361). What is left
     * for this branch is two cases. One is an offboarding whose row has reached
     * {@code maxAttempts}: only a row with attempts left counts as pending, since an exhausted one
     * is never worked again and must not hold the employee's assignments open forever. The other is
     * an offboarded status that never had a row, because it was written outside those two paths
     * (a data fix, say); no code does that today, and this branch is its backstop.
     */
    @Query("""
            SELECT a FROM EmployeeLocationAssignment a
            WHERE a.status = 'ACTIVE'
              AND a.employee.status IN :employeeStatuses
              AND (a.effectiveTo < :date
                   OR (a.effectiveTo IS NULL
                       AND a.employee.statusEffectiveAt < :settledBefore
                       AND NOT EXISTS (
                            SELECT 1 FROM EmployeeOffboardingRetry r
                            WHERE r.employeeId = a.employee.personId
                              AND r.attempts < :maxAttempts)))
            ORDER BY a.effectiveFrom, a.id
            """)
    @NonNull
    List<EmployeeLocationAssignment> findOpenForOffboardedEmployees(
            @Param("date") @NonNull LocalDate date,
            @Param("settledBefore") @NonNull Instant settledBefore,
            @Param("employeeStatuses") @NonNull Collection<EmployeeStatus> employeeStatuses,
            @Param("maxAttempts") int maxAttempts);

    /**
     * {@link #findActiveByPersonIdAndDate} batched across several people in one query
     * (durion#2155): the employee register's location column enriches a page window of ~20-100
     * employees, and one query per row would be exactly the per-row cost #2155 exists to remove.
     * Ordered by person then primary-first so a caller grouping by {@code getPersonId()} finds
     * any flagged-primary assignment first within each person's sublist without a second pass.
     */
    @Query("""
            SELECT a FROM EmployeeLocationAssignment a
            WHERE a.employee.personId IN :personIds
              AND a.status = 'ACTIVE'
              AND a.effectiveFrom <= :date
              AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date)
            ORDER BY a.employee.personId, a.isPrimary DESC, a.effectiveFrom DESC
            """)
    @NonNull
    List<EmployeeLocationAssignment> findActiveByPersonIdIn(
            @Param("personIds") @NonNull Collection<UUID> personIds, @Param("date") @NonNull LocalDate date);

    /**
     * Whether an active assignment of this person, location and role already covers any part of the
     * candidate's effective window.
     *
     * <p>The check is an {@link AssignmentOverlapSearch} specification rather than a JPQL string of
     * {@code (:effectiveTo IS NULL OR …)} clauses: see that class for why the string form made
     * PostgreSQL reject the statement whenever the caller supplied an {@code effectiveTo}, while the
     * same call without one succeeded (issue #1891).
     *
     * @param personId the person being assigned
     * @param locationId the location being assigned to
     * @param role the role being assigned
     * @param effectiveFrom the candidate's inclusive start
     * @param effectiveTo the candidate's inclusive end, or null for an open-ended candidate
     * @return whether any active assignment overlaps
     */
    default boolean existsOverlapping(
            @NonNull UUID personId,
            @NonNull UUID locationId,
            @NonNull String role,
            @NonNull LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {
        return exists(AssignmentOverlapSearch.matching(personId, locationId, role, effectiveFrom, effectiveTo, null));
    }

    /**
     * {@link #existsOverlapping} for an assignment being updated: the assignment itself is excluded,
     * so it cannot conflict with its own current window.
     *
     * @param assignmentId the assignment being updated
     * @param personId the person being assigned
     * @param locationId the location being assigned to
     * @param role the role being assigned
     * @param effectiveFrom the candidate's inclusive start
     * @param effectiveTo the candidate's inclusive end, or null for an open-ended candidate
     * @return whether any other active assignment overlaps
     */
    default boolean existsOverlappingExcludingId(
            @NonNull UUID assignmentId,
            @NonNull UUID personId,
            @NonNull UUID locationId,
            @NonNull String role,
            @NonNull LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {
        return exists(
                AssignmentOverlapSearch.matching(personId, locationId, role, effectiveFrom, effectiveTo, assignmentId));
    }
}
