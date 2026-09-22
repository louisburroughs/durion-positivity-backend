package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.ExtRoleAssignmentReplica;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtRoleAssignmentReplicaRepository extends JpaRepository<ExtRoleAssignmentReplica, UUID> {

    /**
     * Every replicated assignment for a batch of usernames, active or not. One call for a whole
     * page of employees, never one call per row — see {@code ExtRoleAssignmentReplicaService} for
     * why that matters.
     */
    @NonNull
    List<ExtRoleAssignmentReplica> findByUsernameIn(@NonNull Collection<String> usernames);

    /**
     * The active-only slice of the same batch (DECISION-PEOPLE-026): not revoked, and currently
     * inside {@code [effectiveStartDate, effectiveEndDate)} — half-open, matching
     * {@code RoleAssignment.isEffectiveAt} in pos-security-service, the only other place this
     * window's boundary is defined.
     */
    @Query("""
            SELECT r FROM ExtRoleAssignmentReplica r
            WHERE r.username IN :usernames
              AND r.revokedAt IS NULL
              AND r.effectiveStartDate <= :asOf
              AND (r.effectiveEndDate IS NULL OR r.effectiveEndDate > :asOf)
            ORDER BY r.username
            """)
    @NonNull
    List<ExtRoleAssignmentReplica> findActiveByUsernameIn(
            @Param("usernames") @NonNull Collection<String> usernames, @Param("asOf") @NonNull LocalDateTime asOf);
}
