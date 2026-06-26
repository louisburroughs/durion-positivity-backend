package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeLocationAssignmentRepository extends JpaRepository<EmployeeLocationAssignment, UUID> {

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

    @Query("""
            SELECT COUNT(a) > 0 FROM EmployeeLocationAssignment a
            WHERE a.employee.personId = :personId
              AND a.locationId = :locationId
              AND a.role = :role
              AND a.status = 'ACTIVE'
              AND (a.effectiveTo IS NULL OR a.effectiveTo >= :effectiveFrom)
              AND (:effectiveTo IS NULL OR a.effectiveFrom <= :effectiveTo)
            """)
    boolean existsOverlapping(
            @Param("personId") @NonNull UUID personId,
            @Param("locationId") @NonNull UUID locationId,
            @Param("role") @NonNull String role,
            @Param("effectiveFrom") @NonNull LocalDate effectiveFrom,
            @Param("effectiveTo") LocalDate effectiveTo);

    @Query("""
            SELECT COUNT(a) > 0 FROM EmployeeLocationAssignment a
            WHERE a.id <> :assignmentId
              AND a.employee.personId = :personId
              AND a.locationId = :locationId
              AND a.role = :role
              AND a.status = 'ACTIVE'
              AND (a.effectiveTo IS NULL OR a.effectiveTo >= :effectiveFrom)
              AND (:effectiveTo IS NULL OR a.effectiveFrom <= :effectiveTo)
            """)
    boolean existsOverlappingExcludingId(
            @Param("assignmentId") @NonNull UUID assignmentId,
            @Param("personId") @NonNull UUID personId,
            @Param("locationId") @NonNull UUID locationId,
            @Param("role") @NonNull String role,
            @Param("effectiveFrom") @NonNull LocalDate effectiveFrom,
            @Param("effectiveTo") LocalDate effectiveTo);
}
