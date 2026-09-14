package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtStaffingAssignmentReplicaRepository extends JpaRepository<ExtStaffingAssignmentReplica, UUID> {

    @NonNull
    List<ExtStaffingAssignmentReplica> findByPersonIdAndStatus(@NonNull UUID personId, @NonNull String status);

    /**
     * Every assignment this service holds for a person, whatever its role or status — the evidence
     * that lets a missing mechanic row be called a real 404 rather than replication lag (#1987).
     */
    @NonNull
    List<ExtStaffingAssignmentReplica> findByPersonId(@NonNull UUID personId);

    boolean existsByLocationIdAndStatus(@NonNull UUID locationId, @NonNull String status);
}
