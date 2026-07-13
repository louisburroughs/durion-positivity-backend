package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtStaffingAssignmentReplicaRepository extends JpaRepository<ExtStaffingAssignmentReplica, UUID> {

    @NonNull
    List<ExtStaffingAssignmentReplica> findByPersonIdAndStatus(@NonNull UUID personId, @NonNull String status);

    boolean existsByLocationIdAndStatus(@NonNull UUID locationId, @NonNull String status);
}
