package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MechanicRepository extends JpaRepository<Mechanic, UUID> {
    Optional<Mechanic> findByPersonId(@NonNull UUID personId);

    List<Mechanic> findAllByStatus(MechanicStatus status);

    @NonNull
    List<Mechanic> findAllByPersonIdIn(@NonNull Collection<UUID> personIds);

    /**
     * The HR-synchronized roster, optionally narrowed to people holding a credential for {@code
     * skillCode} on {@code onDate} (CAP-328). The filter matches the Durion skill code or the
     * issuer's own code, uppercase-and-trimmed on both sides. The parameter is CAST to String
     * on purpose: a bare {@code :skillCode} inside {@code TRIM} gives Hibernate no type to bind a
     * null with, Postgres infers {@code bytea}, and the whole query fails with "btrim(bytea)
     * does not exist" whenever the filter is absent. It counts a credential as held only
     * while it is neither revoked nor superseded by its owner and not past {@code expiresOn} on
     * that date — a null {@code expiresOn} never expires. It sits in the query so paging is right.
     */
    @Query("""
            SELECT mechanic
            FROM Mechanic mechanic
            WHERE mechanic.status = :status
              AND (:skillCode IS NULL OR EXISTS (
                SELECT credential.credentialId
                FROM ExtPersonCredentialReplica credential
                WHERE credential.personId = mechanic.personId
                  AND credential.status NOT IN ('REVOKED', 'SUPERSEDED')
                  AND (credential.expiresOn IS NULL OR credential.expiresOn >= :onDate)
                  AND (UPPER(TRIM(credential.skillCode)) = UPPER(TRIM(CAST(:skillCode AS String)))
                       OR UPPER(TRIM(credential.sourceCredentialCode)) = UPPER(TRIM(CAST(:skillCode AS String))))))
            """)
    @NonNull
    Page<Mechanic> findRoster(
            @Param("status") @NonNull MechanicStatus status,
            @Param("skillCode") @Nullable String skillCode,
            @Param("onDate") @NonNull LocalDate onDate,
            @NonNull Pageable pageable);

    /**
     * One location's technicians: mechanics whose person holds an ACTIVE TECHNICIAN staffing
     * assignment at {@code locationId} on the {@code ext_people_staffing_assignment} replica —
     * the one place this module learns who works where (CAP-328 replaces the unwritten {@code
     * technician} table and its cast cross-join). The assignment must be effective on {@code
     * onDate} (open-ended bounds allowed), the same coverage {@code SkillRequirementResolver.covers}
     * applies when a booking is judged, so the roster never lists a technician that a booking on
     * that date would not count (#2140). The credential filter is {@link #findRoster}'s.
     * Ordered by mechanic name then person id; callers pass a sort-free {@link Pageable}.
     */
    @Query("""
            SELECT mechanic
            FROM Mechanic mechanic
            WHERE mechanic.status = :status
              AND EXISTS (
                SELECT assignment.assignmentId
                FROM ExtStaffingAssignmentReplica assignment
                WHERE assignment.locationId = :locationId
                  AND assignment.personId = mechanic.personId
                  AND assignment.role = 'TECHNICIAN'
                  AND assignment.status = 'ACTIVE'
                  AND (assignment.effectiveFrom IS NULL OR assignment.effectiveFrom <= :onDate)
                  AND (assignment.effectiveTo IS NULL OR assignment.effectiveTo >= :onDate))
              AND (:skillCode IS NULL OR EXISTS (
                SELECT credential.credentialId
                FROM ExtPersonCredentialReplica credential
                WHERE credential.personId = mechanic.personId
                  AND credential.status NOT IN ('REVOKED', 'SUPERSEDED')
                  AND (credential.expiresOn IS NULL OR credential.expiresOn >= :onDate)
                  AND (UPPER(TRIM(credential.skillCode)) = UPPER(TRIM(CAST(:skillCode AS String)))
                       OR UPPER(TRIM(credential.sourceCredentialCode)) = UPPER(TRIM(CAST(:skillCode AS String))))))
            ORDER BY mechanic.lastName, mechanic.firstName, mechanic.personId
            """)
    @NonNull
    Page<Mechanic> findRosterByLocation(
            @Param("locationId") @NonNull UUID locationId,
            @Param("status") @NonNull MechanicStatus status,
            @Param("skillCode") @Nullable String skillCode,
            @Param("onDate") @NonNull LocalDate onDate,
            @NonNull Pageable pageable);
}
