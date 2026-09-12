package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import java.time.LocalDate;
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
