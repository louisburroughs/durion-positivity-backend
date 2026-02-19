package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.PersonLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonLocationAssignmentRepository extends JpaRepository<PersonLocationAssignment, UUID> {

    List<PersonLocationAssignment> findByPersonId(@NonNull UUID personId);

    Optional<PersonLocationAssignment> findFirstByPersonIdAndIsPrimaryTrueAndStatus(
            @NonNull UUID personId, @NonNull AssignmentStatus status);

    @Query("""
            SELECT COUNT(a) > 0 FROM PersonLocationAssignment a
            WHERE a.personId = :personId
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
            @Param("effectiveTo") LocalDate effectiveTo
    );
}