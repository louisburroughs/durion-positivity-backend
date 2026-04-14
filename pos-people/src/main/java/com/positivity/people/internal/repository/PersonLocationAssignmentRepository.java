package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.PersonLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonLocationAssignmentRepository extends JpaRepository<PersonLocationAssignment, UUID> {

    List<PersonLocationAssignment> findByPerson_Id(@NonNull UUID personId);

    Optional<PersonLocationAssignment> findFirstByPerson_IdAndIsPrimaryTrueAndStatus(
            @NonNull UUID personId, @NonNull AssignmentStatus status);

    @Query("""
            SELECT a FROM PersonLocationAssignment a
            WHERE a.status = 'ACTIVE'
              AND a.effectiveFrom <= :date
              AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date)
              AND (:locationId IS NULL OR a.locationId = :locationId)
            ORDER BY a.locationId, a.person.id, a.effectiveFrom DESC
            """)
    @NonNull
    List<PersonLocationAssignment> findActiveByDateAndOptionalLocation(
            @Param("date") @NonNull LocalDate date, @Param("locationId") UUID locationId);

    @Query("""
            SELECT a FROM PersonLocationAssignment a
            WHERE a.person.id = :personId
              AND a.status = 'ACTIVE'
              AND a.effectiveFrom <= :date
              AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date)
            ORDER BY a.isPrimary DESC, a.effectiveFrom DESC
            """)
    @NonNull
    List<PersonLocationAssignment> findActiveByPersonIdAndDate(
            @Param("personId") @NonNull UUID personId, @Param("date") @NonNull LocalDate date);

    @Query("""
            SELECT COUNT(a) > 0 FROM PersonLocationAssignment a
            WHERE a.person.id = :personId
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
            SELECT COUNT(a) > 0 FROM PersonLocationAssignment a
            WHERE a.id <> :assignmentId
              AND a.person.id = :personId
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
