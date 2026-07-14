package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtStaffingAssignmentReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtStaffingAssignmentReplicaRepository extends JpaRepository<ExtStaffingAssignmentReplica, UUID> {

    @NonNull
    List<ExtStaffingAssignmentReplica> findByLocationIdAndStatus(@NonNull UUID locationId, @NonNull String status);

    @NonNull
    List<ExtStaffingAssignmentReplica> findByPersonIdAndStatusAndPrimaryTrue(
            @NonNull UUID personId, @NonNull String status);
}
