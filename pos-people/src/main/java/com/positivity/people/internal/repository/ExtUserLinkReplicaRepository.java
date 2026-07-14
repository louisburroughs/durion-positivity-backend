package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.ExtUserLinkReplica;
import com.positivity.people.internal.enums.EmployeeStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtUserLinkReplicaRepository extends JpaRepository<ExtUserLinkReplica, UUID> {

    @NonNull
    List<ExtUserLinkReplica> findByPersonIdIn(@NonNull Collection<UUID> personIds);

    @NonNull
    Optional<ExtUserLinkReplica> findFirstByUsername(@NonNull String username);

    boolean existsByUsernameAndPersonId(@NonNull String username, @NonNull UUID personId);

    @NonNull
    List<ExtUserLinkReplica> findByPersonIdAndStatus(@NonNull UUID personId, @NonNull String status);

    @NonNull
    Optional<ExtUserLinkReplica> findFirstByPersonIdAndStatusOrderByLinkIdDesc(
            @NonNull UUID personId, @NonNull String status);

    /**
     * Compliance check (ADR-0015 §4, #888): links whose user account is still in the given link
     * status but whose person sits in one of the given employment statuses. The replica is flat
     * (no relationship to {@code Employee}), so the join targets the shared {@code personId}.
     */
    @Query("""
            SELECT l FROM ExtUserLinkReplica l
            WHERE l.status = :status
              AND EXISTS (SELECT 1 FROM Employee e
                          WHERE e.personId = l.personId AND e.status IN :personStatuses)
            ORDER BY l.username
            """)
    @NonNull
    List<ExtUserLinkReplica> findLinksByStatusForEmployeeStatuses(
            @Param("status") @NonNull String status,
            @Param("personStatuses") @NonNull Collection<EmployeeStatus> personStatuses);
}
