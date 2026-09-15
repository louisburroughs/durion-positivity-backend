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

    /**
     * Every ACTIVE staffing row for one person, regardless of site or {@code is_primary} (#1990).
     * The site-eligibility check filters this by effective date and then by site itself; unlike
     * {@link #findByPersonIdAndStatusAndPrimaryTrue} it does not assume the primary assignment is
     * the only one worth checking, since a technician may be staffed at more than one site.
     */
    @NonNull
    List<ExtStaffingAssignmentReplica> findByPersonIdAndStatus(@NonNull UUID personId, @NonNull String status);
}
